(ns clj-simple-stats.core
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
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

(defn- log [_ req resp user-id]
  (let [now        (LocalDateTime/now UTC)
        dir        (io/file "clj_simple_stats")
        _          (.mkdirs dir)
        file       (io/file dir (str (.format date-formatter now) ".csv"))
        new?       (not (.exists file))
        path       (:uri req)
        query      (:query-string req)
        ip         (or
                     (get (:headers req) "x-forwarded-for")
                     (:remote-addr req))
        user-agent (get (:headers req) "user-agent")
        referrer   (some-> (get (:headers req) "referer")
                     (str/replace #"[\t\n]" ""))]
    (with-open [wrt (io/writer file :append true)]
      (when new?
        (.write wrt (str "date\ttime\tpath\tquery\tcontent-type\tuser-id\tip\tuser-agent\treferrer\n")))
      (.write wrt
        (str
          (.format date-formatter now) "\t"
          (.format time-formatter now) "\t"
          path                "\t"
          query               "\t"
          (content-type resp) "\t"
          user-id             "\t"
          ip                  "\t"
          user-agent          "\t"
          referrer            "\n")))
    nil))

(defn parse-line [line]
  (let [[date time path query content-type user-id ip user-agent referrer] (str/split line #"\t")]
    {:date         date
     :time         time
     :path         path
     :query        query
     :content-type content-type
     :user-id      user-id
     :ip           ip
     :user-agent   user-agent
     :referrer     referrer}))

(defn- loggable? [resp]
  (let [status       (:status resp 200)
        content-type (content-type resp)]
    (and
      (= 200 status)
      (or
        (some-> content-type (str/starts-with? "text/html"))
        (some-> content-type (str/starts-with? "application/atom+xml"))
        (some-> content-type (str/starts-with? "application/rss+xml"))))))

(defn- page-all [req]
  (let [file     (io/file "clj_simple_stats" "all.csv")
        data     (if (.exists file)
                   (with-open [rdr (io/reader file)]
                     (doall
                       (for [line (next (line-seq rdr))] ; skip header
                         (let [[date visitors subscribers] (str/split line #"\t")]
                           {:date        date
                            :visitors    (parse-long visitors)
                            :subscribers (parse-long subscribers)}))))
                   [])
        max-val  (reduce (fn [acc {:keys [visitors subscribers]}]
                           (max acc (+ visitors subscribers)))
                   0
                   data)
        by-month (group-by #(subs (:date %) 0 7) data)
        months   (sort (keys by-month))
        sb       (StringBuilder.)
        append   #(doseq [s %&]
                    (.append sb (str s)))]
    (append "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><style>")
    (append "body { margin: 0; padding: 0; }")
    (append ".container { max-width: 100vw; overflow-x: auto; }")
    (append ".stats { display: flex; flex-direction: row; gap: 2px; margin: 20px 10px; width: fit-content; }")
    (append ".month { display: flex; flex-direction: column; }")
    (append ".bars { height: 200px; display: flex; flex-direction: row; align-items: flex-end; }")
    (append ".label { font-size: 9px; white-space: nowrap; }")
    (append ".day { width: 2px; display: flex; flex-direction: column; }")
    (append ".day:hover .v, .day:hover .s { opacity: 0.5; }")
    (append ".v { background-color: #669BBC; }")
    (append ".s { background-color: #A7C957; }")
    (append "</style></head><body><div class=\"container\" id=\"container\"><div class=\"stats\">")

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
            (append "</div>")))
        (append "</div>")
        (append "<div class=\"label\">" month "</div>")
        (append "</div>")))

    (append "</div></div><script>document.getElementById('container').scrollLeft=document.getElementById('container').scrollWidth;</script></body></html>")
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body    (.toString sb)}))

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
  ([handler {:keys [cookie-name cookie-opts uri]
             :or {cookie-name "stats_id"
                  uri         "/stats"}}]
   (let [cookie-re   (re-pattern (str "(?<=" cookie-name "=)[a-z0-9]+"))
         cookie-opts (str
                       (if-some [domain (:domain cookie-opts)] (str "; Domain=" domain) "")
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
           (page-all req))
         (let [old-cookie (re-find cookie-re (-> req :headers (get "cookie") (or "")))
               user-id    (or old-cookie (random-user-id))
               resp       (handler req)]
           (when (loggable? resp)
             (send log-agent log req resp user-id))
           (cond-> resp
             (nil? old-cookie)
             (update :headers update "Set-Cookie" (fnil conj [])
               (str cookie-name "=" user-id cookie-opts)))))))))
