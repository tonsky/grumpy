(ns clj-simple-stats.core
  (:require
   [clj-simple-stats.analyzer :as analyzer]
   [clj-simple-stats.pages :as pages]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [ring.middleware.cookies :as cookies]
   [ring.middleware.params :as params])
  (:import
   [java.io File]
   [java.sql DriverManager]
   [java.time LocalDate LocalTime LocalDateTime ZoneId]
   [java.time.format DateTimeFormatter]
   [java.util ArrayList UUID]
   [java.util.concurrent LinkedBlockingQueue]
   [org.duckdb DuckDBConnection]))

(def ^LinkedBlockingQueue queue
  (LinkedBlockingQueue.))

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

(defn- init-db! [db-path]
  (with-open [conn (DriverManager/getConnection (str "jdbc:duckdb:" db-path))
              stmt (.createStatement conn)]
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
         uniq       UUID
       )")))

(defn- loggable? [req resp]
  (let [status (:status resp 200)
        mime   (content-type resp)]
    (and
      (= 200 status)
      (or
        (some-> mime (str/starts-with? "text/html"))
        (some-> mime (str/starts-with? "application/atom+xml"))
        (some-> mime (str/starts-with? "application/rss+xml"))))))

(defn- schedule-line! [req resp user-id]
  (when (loggable? req resp)
    (let [now  (LocalDateTime/now UTC)
          mime (content-type resp)
          line (-> {:date       (.toLocalDate now)
                    :time       (.toLocalTime now)
                    :path       (:uri req)
                    :query      (:query-string req)
                    :ip         (or
                                  (get (:headers req) "x-forwarded-for")
                                  (:remote-addr req))
                    :user-agent (get (:headers req) "user-agent")
                    :referrer   (get (:headers req) "referer")
                    :type       (cond
                                  (some-> mime (str/starts-with? "application/atom+xml")) "feed"
                                  (some-> mime (str/starts-with? "application/rss+xml"))  "feed")
                    :uniq       user-id})]
      (.add queue line))))

(defn- insert-lines! [db-path lines]
  (println "Inserting" (count lines) "lines to" db-path)
  (with-open [conn ^DuckDBConnection (DriverManager/getConnection (str "jdbc:duckdb:" db-path))
              app  (.createAppender conn DuckDBConnection/DEFAULT_SCHEMA "stats")]
    (doseq [line lines
            :let [line' (analyzer/analyze line)]]
      (.beginRow app)
      (.append app ^LocalDate (:date line'))
      (.append app ^LocalTime (:time line'))
      (.append app ^String    (:path line'))
      (.append app ^String    (:query line'))
      (.append app ^String    (:ip line'))
      (.append app ^String    (:user-agent line'))
      (.append app ^String    (:referrer line'))
      (.append app ^String    (:type line'))
      (.append app ^String    (:agent line'))
      (.append app ^String    (:os line'))
      (.append app            (int (:mult line')))
      (.append app ^UUID      (:uniq line'))
      (.endRow app))
    (.flush app))
  nil)

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
            (println (.getName (Thread/currentThread)) "graceful shutdown")))))
    (.setDaemon true)
    (.setName (str "clj-simple-stats.core/worker(path=" db-path ")"))
    (.start)))

(defn wrap-stats
  ([handler]
   (wrap-stats handler {}))
  ([handler {:keys [cookie-name cookie-opts uri db-path]
             :or {cookie-name "stats_id"
                  uri         "/stats"
                  db-path     "clj_simple_stats.duckdb"}}]
   (some-> @*worker Thread/.interrupt)
   (init-db! db-path)
   (reset! *worker (start-worker! db-path))
   (fn [req]
     (let [req (-> req
                 cookies/cookies-request)]
       (if (= uri (:uri req))
         (let [params (:query-params (params/params-request req))]
           (cond
             (get params "month") (pages/page-month req (get params "month"))
             (get params "path")  (pages/page-path req (get params "path"))
             :else                (pages/page-all req db-path)))
         (let [old-cookie (some-> req :cookies (get cookie-name) :value parse-uuid)
               user-id    (or old-cookie (random-uuid))
               resp       (handler req)]
           (when (loggable? req resp)
             (schedule-line! req resp user-id))
           (cond-> resp
             (nil? old-cookie)
             (update :cookies assoc cookie-name
               (merge
                 {:max-age   2147483647
                  :path      "/"
                  :http-only true}
                 cookie-opts
                 {:value (str user-id)}))

             (nil? old-cookie)
             (cookies/cookies-response))))))))

(defn before-ns-unload []
  (some-> @*worker Thread/.interrupt))
