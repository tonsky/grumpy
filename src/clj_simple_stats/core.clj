(ns clj-simple-stats.core
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   [java.sql DriverManager]
   [java.time LocalDateTime ZoneId]
   [java.time.format DateTimeFormatter]))

(defn- random-user-id []
  (str/join (repeatedly 12 #(rand-nth "abcdefghijklmnopqrstuvwxyz0123456789"))))

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

(def ^:private log-agent
  (agent nil))

(defn- init-db! [db-path]
  (let [conn (DriverManager/getConnection (str "jdbc:duckdb:" db-path))
        stmt (.createStatement conn)]
    (try
      (.execute stmt
        "CREATE TABLE IF NOT EXISTS stats (
           date DATE,
           time TIME,
           path VARCHAR,
           query VARCHAR,
           content_type VARCHAR,
           user_id VARCHAR,
           ip VARCHAR,
           user_agent VARCHAR,
           referrer VARCHAR
         )")
      (finally
        (.close stmt)
        (.close conn)))))

(defn- log [_ db-path req resp user-id]
  (let [now        (LocalDateTime/now UTC)
        path       (:uri req)
        query      (:query-string req)
        ip         (or
                     (get (:headers req) "x-forwarded-for")
                     (:remote-addr req))
        user-agent (get (:headers req) "user-agent")
        referrer   (some-> (get (:headers req) "referer")
                     (str/replace #"[\t\n]" ""))
        conn       (DriverManager/getConnection (str "jdbc:duckdb:" db-path))
        stmt       (.prepareStatement conn
                     "INSERT INTO stats (date, time, path, query, content_type, user_id, ip, user_agent, referrer)
                      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")]
    (try
      (.setObject stmt 1 (.toLocalDate now))
      (.setObject stmt 2 (.toLocalTime now))
      (.setString stmt 3 path)
      (.setString stmt 4 query)
      (.setString stmt 5 (content-type resp))
      (.setString stmt 6 user-id)
      (.setString stmt 7 ip)
      (.setString stmt 8 user-agent)
      (.setString stmt 9 referrer)
      (.execute stmt)
      (finally
        (.close stmt)
        (.close conn)))
    nil))

(defn- loggable? [resp]
  (let [status       (:status resp 200)
        content-type (content-type resp)]
    (and
      (= 200 status)
      (or
        (some-> content-type (str/starts-with? "text/html"))
        (some-> content-type (str/starts-with? "application/atom+xml"))
        (some-> content-type (str/starts-with? "application/rss+xml"))))))

(defn- page-all [req db-path]
  (let [sb       (StringBuilder.)
        append   #(doseq [s %&]
                    (.append sb (str s)))]
    (append "<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
    (append "<style>")
    (append "body { margin: 0; padding: 0; }")
    (append ".container { max-width: 100vw; overflow-x: auto; }")
    (append ".stats { display: flex; flex-direction: row; gap: 2px; margin: 20px 10px; width: fit-content; }")
    (append ".by_month .bars { height: 200px; display: flex; flex-direction: row; align-items: flex-end; gap: 2px; }")
    (append ".by_month .month { width: 12px; display: flex; flex-direction: column; }")
    (append ".by_month .month:hover .v, .month:hover .s { opacity: 0.5; }")
    (append ".by_month .label { font-size: 9px; white-space: nowrap; writing-mode: vertical-lr; transform: rotate(180deg); margin-top: 5px; }")

    (append ".by_day .bars { height: 200px; display: flex; flex-direction: row; align-items: flex-end; }")
    (append ".by_day .month { display: flex; flex-direction: column; }")
    (append ".by_day .bars { height: 200px; display: flex; flex-direction: row; align-items: flex-end; }")
    (append ".by_day .label { font-size: 9px; white-space: nowrap; }")
    (append ".by_day .day { width: 2px; display: flex; flex-direction: column; }")
    (append ".by_day .day:hover .v, .day:hover .s { opacity: 0.5; }")

    (append ".v { background-color: #669BBC40; border-top: 2px solid #669BBC; }")
    (append ".s { background-color: #A7C957; }")

    (append "</style>")
    (append "</head><body>")

    (with-open [conn (DriverManager/getConnection (str "jdbc:duckdb:" db-path))]
      (let [data     (with-open [stmt     (.createStatement conn)
                                 rs       (.executeQuery stmt
                                            "SELECT strftime(date, '%Y-%m') as month,
                                                    COUNT(DISTINCT ip) as visitors,
                                                    0 as subscribers
                                             FROM stats
                                             GROUP BY month
                                             ORDER BY month")]
                       (loop [acc []]
                         (if (.next rs)
                           (recur (conj acc
                                    {:month       (.getString rs "month")
                                     :visitors    (.getLong rs "visitors")
                                     :subscribers (.getLong rs "subscribers")}))
                           acc)))
            max-val  (reduce (fn [acc {:keys [visitors subscribers]}]
                               (max acc (+ visitors subscribers)))
                       0
                       data)]
        (append "<div class=\"container\">")
        (append "<div class=\"stats by_month\">")
        (append "<div class=\"bars\">")
        (doseq [{:keys [month visitors subscribers]} data]
          (let [v-height (int (* 200 (/ visitors max-val)))
                s-height (int (* 2000 (/ subscribers max-val)))]
            (append "<div class=\"month\" title=\"" month "\nvisitors: " visitors "\nsubscibers: " subscribers "\">")
            (append "<div class=\"v\" style=\"height: " v-height "px\"></div>")
            (append "<div class=\"s\" style=\"height: " s-height "px\"></div>")
            (append "<div class=\"label\">" month "</div>")
            (append "</div>")))
        (append "</div></div></div>"))

      (let [data     (with-open [stmt     (.createStatement conn)
                                 rs       (.executeQuery stmt
                                            "SELECT date::VARCHAR as date,
                                                    COUNT(DISTINCT ip) as visitors,
                                                    0 as subscribers
                                             FROM stats
                                             GROUP BY date
                                             ORDER BY date")]
                       (loop [acc []]
                         (if (.next rs)
                           (recur (conj acc
                                    {:date       (.getString rs "date")
                                     :visitors    (.getLong rs "visitors")
                                     :subscribers (.getLong rs "subscribers")}))
                           acc)))
            max-val  (reduce (fn [acc {:keys [visitors subscribers]}]
                               (max acc (+ visitors subscribers)))
                       0
                       data)
            by-month (group-by #(subs (:date %) 0 7) data)
            months   (sort (keys by-month))]
        (append "<div class=\"container\">")
        (append "<div class=\"stats by_day\">")
        (doseq [month months]
          (let [days (sort-by :date (get by-month month))]
            (append "<div class=\"month\">")
            (append "<div class=\"bars\">")
            (doseq [{:keys [date visitors subscribers]} days]
              (let [v-height (int (* 200 (/ visitors max-val)))
                    s-height (int (* 2000 (/ subscribers max-val)))]
                (append "<div class=\"day\" title=\"" date "\nvisitors: " visitors "\nsubscibers: " subscribers "\">")
                (append "<div class=\"v\" style=\"height: " v-height "px\"></div>")
                (append "<div class=\"s\" style=\"height: " s-height "px\"></div>")
                (append "</div>"))) ;; .day
            (append "</div>") ;; .bars
            (append "<div class=\"label\">" month "</div>")
            (append "</div>")))) ;; .month
      (append "</div></div>") ;; .stats .container

      (append "<script>
                 document.querySelectorAll('.container').forEach((el) => {
                   el.scrollLeft = el.scrollWidth;
                 });
               </script>")
      (append "</body></html>")
      {:status 200
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body    (.toString sb)})))

(defn- page-month [req month]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body    (str "page-month: " month)})

(defn- page-path [req path]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body    (str "page-path: " path)})

; stats_id=bc1k8uxirrls; Path=/; Max-Age=2147483647; HttpOnly

(defn wrap-stats
  ([handler]
   (wrap-stats handler {}))
  ([handler {:keys [cookie-name cookie-opts uri db-path]
             :or {cookie-name "stats_id"
                  uri         "/stats"
                  db-path     "clj_simple_stats.duckdb"}}]
   (init-db! db-path)
   (let [cookie-re   (re-pattern (str "(?<=" cookie-name "=)[a-z0-9]+"))
         cookie-opts (str
                       (if-some [domain  (:domain cookie-opts)] (str "; Domain=" domain) "")
                       (if-some [max-age (:max-age cookie-opts 2147483647)] (str "; Max-Age=" max-age) "")
                       (if-some [path    (:path cookie-opts "/")] (str "; Path=" path) "")
                       (if (:secure cookie-opts) "; Secure" "")
                       ;; TODO :expires "; Expires="
                       (if (:http-only cookie-opts) "; HttpOnly" "")
                       (if-some [same-site (:same-site cookie-opts)] (str "; SameSite=" (str/capitalize (name same-site))) "")
                       (if (:partitioned cookie-opts) "; Partitioned" ""))]
     (fn [req]
       (if (= uri (:uri req))
         (condp re-find (or (:query-string req) "")
           #"(?<=month=)[0-9\-]+" :>> (fn [month] (page-month req month))
           #"(?<=path=)[^&]+" :>> (fn [path] (page-path req path))
           (page-all req db-path))
         (let [old-cookie (re-find cookie-re (-> req :headers (get "cookie") (or "")))
               user-id    (or old-cookie (random-user-id))
               resp       (handler req)]
           (when (loggable? resp)
             (send log-agent log db-path req resp user-id))
           (cond-> resp
             (nil? old-cookie)
             (update :headers update "Set-Cookie" (fnil conj [])
               (str cookie-name "=" user-id cookie-opts)))))))))
