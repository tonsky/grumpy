(ns clj-simple-stats.core
  (:require
   [clj-simple-stats.analyzer :as analyzer]
   [clj-simple-stats.pages :as pages]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [ring.middleware.cookies :as cookies])
  (:import
   [java.io File]
   [java.sql DriverManager]
   [java.time LocalDate LocalTime LocalDateTime ZoneId]
   [java.time.format DateTimeFormatter]
   [java.util ArrayList UUID]
   [java.util.concurrent LinkedBlockingQueue]
   [java.util.concurrent.locks ReentrantLock]
   [org.duckdb DuckDBConnection]))

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
         mult       INTEGER,
         uniq       UUID,
         set_cookie UUID
       )")))

(defmacro with-conn [[sym db-path] & body]
  (let [sym (vary-meta sym assoc :tag DuckDBConnection)]
    `(try
       (.lock db-lock)
       (let [db-path# ~db-path
             exists#  (File/.exists (io/file db-path#))]
         (with-open [~sym ^DuckDBConnection (DriverManager/getConnection (str "jdbc:duckdb:" db-path#))]
           (when-not exists#
             (clj-simple-stats.core/init-db! ~sym))
           ~@body))
       (finally
         (.unlock db-lock)))))

(def *worker
  (atom nil))

(def ^:private ^DateTimeFormatter month-formatter
  (DateTimeFormatter/ofPattern "yyyy-MM"))

(def ^:private ^DateTimeFormatter date-formatter
  (DateTimeFormatter/ofPattern "yyyy-MM-dd"))

(def ^:private ^DateTimeFormatter time-formatter
  (DateTimeFormatter/ofPattern "HH:mm:ss"))

(def ^:private ^ZoneId UTC
  (ZoneId/of "UTC"))

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
  (with-conn [conn db-path]
    #_(println "Inserting" (count lines) "lines to" db-path)
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
        (.append apnd            (int (:mult line')))
        (.append apnd ^UUID      (:uniq line'))
        (.append apnd ^UUID      (:set-cookie line'))
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
                  (println e)))
              (recur)))
          (catch InterruptedException e
            #_(println (.getName (Thread/currentThread)) "graceful shutdown")))))
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
     (if (= uri (:uri req))
       (render-stats req opts)
       (handler req)))))

(defn wrap-stats
  ([handler]
   (wrap-stats handler {}))
  ([handler opts]
   (-> handler
     (wrap-collect-stats opts)
     (wrap-render-stats opts))))

(defn before-ns-unload []
  (some-> @*worker Thread/.interrupt))
