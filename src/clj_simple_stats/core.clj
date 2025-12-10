(ns clj-simple-stats.core
  (:require
   [clj-simple-stats.analyzer :as analyzer]
   [clj-simple-stats.pages :as pages]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [ring.middleware.cookies :as cookies]
   [ring.util.response :as response])
  (:import
   [java.io File]
   [java.lang AutoCloseable]
   [java.sql DriverManager]
   [java.time LocalDate LocalTime LocalDateTime ZoneId]
   [java.time.format DateTimeFormatter]
   [java.util ArrayList UUID]
   [java.util.concurrent LinkedBlockingQueue ScheduledFuture ScheduledThreadPoolExecutor ThreadFactory TimeUnit]
   [java.util.concurrent.locks ReentrantLock]
   [org.duckdb DuckDBConnection]))

(def ^:private ^ZoneId UTC
  (ZoneId/of "UTC"))

(def ^:private default-db-path
  "clj_simple_stats.duckdb")

(def ^:private default-cookie-name
  "stats_id")

(def ^:private default-cookie-opts
  {:max-age   2147483647
   :path      "/"
   :http-only true})

(def ^:private default-uri
  "/stats")

(def ^LinkedBlockingQueue queue
  (LinkedBlockingQueue.))

(def ^ReentrantLock db-lock
  (ReentrantLock.))

(defmacro with-lock [lock & body]
  `(let [lock# ^ReentrantLock ~lock]
     (.lock lock#)
     (try
       (do
         ~@body)
       (finally
         (.unlock lock#)))))

(defmacro log-verbose [& msgs]
  #_`(println ~@msgs))

(defmacro log [& msgs]
  `(println ~@msgs))

(defn init-db! [^DuckDBConnection conn]
  (with-open [stmt (.createStatement conn)]
    (.execute stmt
      "CREATE TYPE IF NOT EXISTS agent_type_t AS ENUM ('feed', 'bot', 'browser')")
    (.execute stmt
      "CREATE TYPE IF NOT EXISTS agent_os_t AS ENUM ('Android', 'Windows', 'iOS', 'macOS', 'Linux')")
    (.execute stmt
      "CREATE TABLE IF NOT EXISTS stats (
         date       DATE,
         time       TIME,
         path       VARCHAR,
         query      VARCHAR,
         ip         VARCHAR,
         user_agent VARCHAR,
         referrer   VARCHAR,
         type       agent_type_t,
         agent      VARCHAR,
         os         agent_os_t,
         ref_domain VARCHAR,
         mult       INTEGER,
         set_cookie UUID,
         uniq       UUID
       )")))

(defn connect ^DuckDBConnection [db-path reason]
  (let [exists  (File/.exists (io/file db-path))
        _       (log-verbose "Opening" db-path reason)
        conn    (DriverManager/getConnection (str "jdbc:duckdb:" db-path))]
    (when-not exists
      (log "Initializing" db-path)
      (init-db! conn))
    conn))

;; {db-path -> {:conn ..., :future ...}}
(def *conns
  (atom {}))

(def conn-ttl-ms
  10000)

(def worker-conn-ttl-ms
  1000)

(def ^ScheduledThreadPoolExecutor scheduler
  (doto
    (ScheduledThreadPoolExecutor.
      1
      (reify ThreadFactory
        (newThread [_ runnable]
          (doto
            (Thread. ^Runnable runnable "clj-simple-stats.core/scheduler")
            (.setDaemon true)))))
    (.setExecuteExistingDelayedTasksAfterShutdownPolicy false)
    (.setRemoveOnCancelPolicy true)))

(def *worker
  (atom nil))

(defn close-runnable ^Runnable [db-path]
  (fn []
    (log-verbose "Closing" db-path "by timeout")
    (with-lock db-lock
      (try
        (-> @*conns (get db-path) :conn AutoCloseable/.close)
        (catch Exception e
          (println e)))
      (swap! *conns dissoc db-path))))

(defmacro with-conn [[sym db-path] & opts+body]
  (let [sym (vary-meta sym assoc :tag 'DuckDBConnection)
        [opts body] (if (map? (first opts+body))
                      [(first opts+body) (next opts+body)]
                      [{} opts+body])]
    `(with-lock db-lock
       (let [db-path#          ~db-path
             new-ttl#          (or (:ttl ~opts) conn-ttl-ms)
             {conn# :conn
              future# :future} (or
                                 (get @*conns db-path#)
                                 {:conn (connect db-path# (str "for " new-ttl# " ms"))})
             old-ttl#          (when future#
                                 (ScheduledFuture/.getDelay future# TimeUnit/MILLISECONDS))
             _#                (when (and future# (< old-ttl# new-ttl#))
                                 (ScheduledFuture/.cancel future# false))
             future#           (if (or (nil? future#) (< old-ttl# new-ttl#))
                                 (do
                                   (log-verbose "Extending" db-path# "to" new-ttl# "ms")
                                   (.schedule scheduler (close-runnable db-path#) (int new-ttl#) TimeUnit/MILLISECONDS))
                                 future#)]
         (swap! *conns assoc db-path# {:conn   conn#
                                       :future future#})
         (let [~sym conn#]
           ~@body)))))

(defn- content-type [resp]
  ((some-fn
     #(get % "Content-Type")
     #(get % "content-type")
     #(get % "Content-type"))
   (:headers resp)))

(defn- loggable? [req resp]
  (let [status (:status resp 200)
        mime   (content-type resp)]
    (and
      (= 200 status)
      (or
        (some-> mime (str/starts-with? "text/html"))
        (some-> mime (str/starts-with? "application/atom+xml"))
        (some-> mime (str/starts-with? "application/rss+xml"))))))

(defn- schedule-line! [req resp opts]
  (when (loggable? req resp)
    (let [now  (LocalDateTime/now UTC)
          mime (content-type resp)
          line (-> {:date       (-> now .toLocalDate)
                    :time       (-> now .toLocalTime (.withNano 0))
                    :path       (:uri req)
                    :query      (:query-string req)
                    :ip         (or
                                  (get (:headers req) "x-forwarded-for")
                                  (:remote-addr req))
                    :user-agent (get (:headers req) "user-agent")
                    :referrer   (get (:headers req) "referer")
                    :type       (cond
                                  (some-> mime (str/starts-with? "application/atom+xml")) "feed"
                                  (some-> mime (str/starts-with? "application/rss+xml"))  "feed")}
                 (merge opts))]
      (.add queue line))))

(defn- insert-lines! [db-path lines]
  (with-conn [conn db-path] {:ttl worker-conn-ttl-ms}
    (log-verbose "Inserting" (count lines) "lines to" db-path)
    (with-open [apnd (.createAppender conn DuckDBConnection/DEFAULT_SCHEMA "stats")]
      (doseq [line lines
              :let [line' (analyzer/analyze line)]]
        (.beginRow apnd)
        (.append apnd ^LocalDate (:date line'))
        (.append apnd ^LocalTime (:time line'))
        (.append apnd ^String    (:path line'))
        (.append apnd ^String    (:query line'))
        (.append apnd ^String    (:ip line'))
        (.append apnd ^String    (:user-agent line'))
        (.append apnd ^String    (:referrer line'))
        (.append apnd ^String    (:type line'))
        (.append apnd ^String    (:agent line'))
        (.append apnd ^String    (:os line'))
        (.append apnd ^String    (:ref-domain line'))
        (.append apnd            (int (:mult line')))
        (.append apnd ^UUID      (:set-cookie line'))
        (.append apnd ^UUID      (:uniq line'))
        (.endRow apnd))
      (.flush apnd))

    (when-some [to-update (->> lines
                            (filter :second-visit?)
                            (map :uniq)
                            (not-empty))]
      (with-open [stmt (.prepareStatement conn "UPDATE stats SET uniq = ? WHERE set_cookie = ?")]
        (doseq [uniq to-update]
          (.setObject stmt 1 uniq)
          (.setObject stmt 2 uniq)
          (.addBatch stmt))
        (.executeBatch stmt)))
    nil))

(defn start-worker! [db-path]
  (doto
    (Thread.
      (fn []
        (try
          (let [buf (ArrayList. 100)]
            (loop []
              (.clear buf)
              (.drainTo queue buf 100)
              (try
                (if (.isEmpty buf)
                  (insert-lines! db-path [(.take queue)]) ;; block here
                  (insert-lines! db-path buf))
                (catch InterruptedException e
                  (throw e))
                (catch Exception e
                  (log e)))
              (recur)))
          (catch InterruptedException e
            (log-verbose (.getName (Thread/currentThread)) "graceful shutdown")))))
    (.setDaemon true)
    (.setName (str "clj-simple-stats.core/worker(path=" db-path ")"))
    (.start)))

(defn wrap-collect-stats
  ([handler]
   (wrap-collect-stats handler {}))
  ([handler {:keys [cookie-name cookie-opts db-path]
             :or {cookie-name default-cookie-name
                  db-path     default-db-path}}]
   (some-> @*worker Thread/.interrupt)
   (reset! *worker (start-worker! db-path))
   (fn [req]
     (let [resp (handler req)]
       (if-not (loggable? req resp)
         ;; pass-through response
         resp

         ;; wrapped response
         (let [old-cookie    (some-> req cookies/cookies-request :cookies (get cookie-name) :value)
               first-visit?  (nil? old-cookie)
               second-visit? (and old-cookie (str/starts-with? old-cookie "?"))
               user-id       (cond
                               (nil? old-cookie) (random-uuid)
                               second-visit?     (parse-uuid (subs old-cookie 1))
                               :else             (parse-uuid old-cookie))]
           (schedule-line! req resp
             {:set-cookie    (when first-visit?
                               user-id)
              :second-visit? second-visit?
              :uniq          (when old-cookie
                               user-id)})
           (cond
             ;; first time visit
             first-visit?
             (-> resp
               (update :cookies assoc cookie-name
                 (merge
                   default-cookie-opts
                   cookie-opts
                   {:value (str "?" user-id)}))
               (cookies/cookies-response))

             ;; second time visit
             second-visit?
             (-> resp
               (update :cookies assoc cookie-name
                 (merge
                   default-cookie-opts
                   cookie-opts
                   {:value (str user-id)}))
               (cookies/cookies-response))

             ;; third+ visit
             :else
             resp)))))))

(defn render-stats
  ([req]
   (render-stats {} req))
  ([{:keys [db-path] :or {db-path default-db-path}} req]
   (with-conn [conn db-path]
     (pages/page conn req))))

(defn wrap-render-stats
  ([handler]
   (wrap-render-stats handler {}))
  ([handler {:keys [uri] :or {uri default-uri} :as opts}]
   (fn [req]
     (cond
       (= uri (:uri req))
       (render-stats opts req)

       (= (str uri "/favicon.ico") (:uri req))
       (response/resource-response "clj_simple_stats/favicon.ico")

       :else
       (handler req)))))

(defn wrap-stats
  ([handler]
   (wrap-stats handler {}))
  ([handler opts]
   (-> handler
     (wrap-collect-stats opts)
     (wrap-render-stats opts))))

(defn before-ns-unload []
  (some-> @*worker Thread/.interrupt)
  (log-verbose "Shutting down pool")
  (.shutdown scheduler)
  (.awaitTermination scheduler 10000 TimeUnit/MILLISECONDS)
  (with-lock db-lock
    (doseq [[db-path {:keys [conn]}] @*conns]
      (try
        (log-verbose "Closing" db-path "because of shutdown")
        (AutoCloseable/.close conn)
        (catch Exception e
          (log e))))))
