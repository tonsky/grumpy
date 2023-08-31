(ns grumpy.stats
  (:require
    [clojure.string :as str]
    [clojure.java.io :as io]
    [grumpy.auth :as auth]
    [grumpy.core.coll :as coll]
    [grumpy.core.log :as log]
    [grumpy.core.routes :as routes]
    [grumpy.core.time :as time]
    [grumpy.core.web :as web]
    [io.pedestal.interceptor :as interceptor]
    [rum.core :as rum])
  (:import
    [java.io File]
    [java.time LocalDateTime Instant]
    [java.time.format DateTimeFormatter]
    [java.time.temporal TemporalQuery TemporalAccessor]))


(def ^DateTimeFormatter month-formatter
  (DateTimeFormatter/ofPattern "yyyy-MM"))


(def ^DateTimeFormatter date-formatter
  (DateTimeFormatter/ofPattern "yyyy-MM-dd"))


(def ^DateTimeFormatter time-formatter
  (DateTimeFormatter/ofPattern "HH:mm:ss"))


(def log-agent
  (agent nil))


(defn log [_ req]
  (let [time       (LocalDateTime/now time/UTC)
        dir        (io/file "grumpy_data/stats")
        _          (.mkdirs dir)
        month      (.format month-formatter time)
        file       (io/file dir (str month ".csv"))
        new?       (not (.exists file))
        path       (:uri req)
        query      (:query-string req)
        ip         (:remote-addr req)
        user-agent (get (:headers req) "user-agent")
        referrer   (get (:headers req) "referer")]
    (with-open [wrt (io/writer file :append true)]
      (when new?
        (.write wrt (str "date\ttime\tpath\tquery\tip\tuser-agent\treferrer\n")))
      (.write wrt
        (str
          (.format date-formatter time) "\t"
          (.format time-formatter time) "\t"
          path       "\t"
          query      "\t"
          ip         "\t"
          user-agent "\t"
          referrer   "\n")))
    nil))


(def interceptor
  (interceptor/interceptor
    {:name ::access-log
     :enter
     (fn [ctx]
       (send log-agent log (:request ctx))
       ctx)}))


(defn parse-line [line]
  (let [[date time path query ip user-agent referrer] (str/split line #"\t")]
    {:date  date
     :time  time
     :path  path
     :query query
     :ip    ip
     :user-agent user-agent
     :referrer   referrer}))


(defn group [key-fn val-fn xs]
  (->> xs
    (reduce
      (fn [acc x]
        (update acc (key-fn x) (fnil conj []) x))
      {})
    (reduce-kv
      (fn [acc k v]
        (assoc acc k (val-fn v)))
      {})))


(defn count-uniques [keys rs]
  (->> rs (map (apply juxt keys)) distinct count))


(defn table [rows]
  (let [max (reduce max 1 (map second rows))]
    [:table
     [:tbody
      (for [[name count] rows]
        [:tr
         [:td.name {:title name} name]
         [:td.val count]
         (let [width (-> count (/ max) (* 100) float (str "%"))]
           [:td.bar [:div {:style {:width width}}]])])]]))


(defn rss-user-agent [rs]
  (let [s     (or (:user-agent rs) "-")
        s     (if-some [i (str/index-of s "compatible;")]
                (subs s (+ i (count "compatible;")))
                s)
        split (->> ["-" "/" "feed-id:" "(" ":" ";"]
                (keep #(str/index-of s %))
                (reduce min (count s)))]
    (-> s
      (subs 0 split)
      str/trim
      (str/replace #"^Mozilla$" "Unknown/Browser-based")
      (str/replace #"RSS Reader" ""))))


(defn count-subscribers [rs]
  (or (some->> rs
        (keep #(re-find #"(\d+) subscribers" (or (:user-agent %) "-")))
        (not-empty)
        (map second)
        (map parse-long)
        (reduce max 0))
    (count-uniques [:ip] rs))) 


(defn compact [limit rs]
  (let [[big small] (split-with (fn [[_ cnt]] (> cnt limit)) rs)]
    (concat
      big
      [["Others" (reduce + 0 (map second small))]])))
  

(rum/defc page [month]
  (let [lines  (with-open [rdr (io/reader (io/file "grumpy_data/stats" (str month ".csv")))]
                 (->> (line-seq rdr)
                   (next)
                   (map parse-line)
                   (doall)))
        months (->> (file-seq (io/file "grumpy_data/stats"))
                 (keep #(second (re-matches #"(\d{4}-\d{2}).csv" (.getName ^File %))))
                 (sort))]
    (web/page {:page :stats}
      (list
        (web/inline-styles
          ".name { overflow-x: hidden; max-width: 300px; text-overflow: ellipsis; white-space: nowrap; }
           .val  { text-align: right; font-feature-settings: \"tnum\"; }
           .name, .val { padding-right: 1em; }
           .bar  { width: 100%; }
           .bar > div { background-color: #CCC; height: 11px; }")
        [:p
         "Month: "
         [:select
          {:on-change (str "location.href = '/stats/' + this.value;")}
          (for [m months]
            [:option {:value    m
                      :selected (= month m)} m])]]
        
        [:h1 "Popular posts"]
        [:p
         (let [url-fn (fn [{:keys [path]}]
                        [:a {:href path} path])]
           (table
             (->> lines
               (filter #(re-matches #"^(/post/[a-zA-Z0-9_\-]+|/[0-9]+)$" (:path %)))
               (group url-fn #(count-uniques [:ip :user-agent] %))
               (sort-by second)
               (reverse)
               (compact 50))))]
        
        [:h1 "Unique visitors"]
        [:p
         (table
           (->> lines
             (filter #(re-matches #"^(/|/post/[a-zA-Z0-9_\-]+|/[0-9]+)$" (:path %)))
             (group :date #(count-uniques [:ip :user-agent] %))
             (sort-by first)))]
        
        [:h1 "RSS Readers"]
        [:p
         (table
           (->> lines
             (filter #(= "/feed.xml" (:path %)))
             (group #(rss-user-agent %) count-subscribers)
             (sort-by second)
             (reverse)
             (compact 50)))]
        ))))

(def routes
  (routes/expand
    [:get "/stats"
     [auth/populate-session auth/require-user]
     (fn [req]
       (web/redirect
         (time/format (time/utc-now) "'/stats/'yyyy-MM")))]
    
    [:get "/stats/:month"
     [auth/populate-session auth/require-user]
     (fn [req]
       (web/html-response
         (page (-> req :path-params :month))))]))


(comment
  (def ^DateTimeFormatter instant-formatter
    (DateTimeFormatter/ofPattern "dd/MMM/yyyy:HH:mm:ss X"))


  (defn parse-instant [s]
    (LocalDateTime/parse s instant-formatter))


  (defn parse-line-nginx [line]
    (let [[ip _ user time request status bytes referrer user-agent]
          (->> line
            (re-seq #"-|\"-\"|\"([^\"]+)\"|\[([^\]]+)\]|([^\"\[\] ]+)")
            (map next)
            (map (fn [[a b c]] (or a b c))))
          [method url protocol] (str/split request #"\s+")]
      (when url
        (let [[_ path query fragment] (re-matches #"([^?#]+)(?:\?([^#]+)?)?(?:#(.+)?)?" url)]
          {:ip         ip
           :user       user
           :time       (parse-instant time)
           :request    request
           :method     method
           :url        url
           :path       path
           :query      query
           :fragment   fragment
           :protocol   protocol
           :status     (some-> status parse-long)
           :bytes      (some-> bytes parse-long)
           :referrer   referrer
           :user-agent user-agent}))))

  
  (defn convert-nginx [from to]
    (with-open [rdr (io/reader (io/file from))
                wrt (io/writer (io/file to))]
      (.write wrt "date\ttime\tpath\tquery\tip\tuser-agent\treferrer\n")
      (doseq [:let [lines (keep parse-line-nginx (line-seq rdr))]
              {:keys [time path query fragment ip user-agent referrer status]} lines
              :when (= 200 status)]
        (.write wrt
          (str
            (.format date-formatter time) "\t"
            (.format time-formatter time) "\t"
            path       "\t"
            query      "\t"
            ip         "\t"
            user-agent "\t"
            referrer   "\n")))))

  
  (convert-nginx "grumpy_data/stats/grumpy_access.log" "grumpy_data/stats/2023-08.csv")
  (convert-nginx "grumpy_data/stats/grumpy_access.log.1" "grumpy_data/stats/2023-07.csv")
  (convert-nginx "grumpy_data/stats/grumpy_access.log.2" "grumpy_data/stats/2023-06.csv")
  (convert-nginx "grumpy_data/stats/grumpy_access.log.3" "grumpy_data/stats/2023-05.csv")
  (convert-nginx "grumpy_data/stats/grumpy_access.log.4" "grumpy_data/stats/2023-04.csv")
  (convert-nginx "grumpy_data/stats/grumpy_access.log.5" "grumpy_data/stats/2023-03.csv")
  (convert-nginx "grumpy_data/stats/grumpy_access.log.6" "grumpy_data/stats/2023-02.csv")
  (convert-nginx "grumpy_data/stats/grumpy_access.log.7" "grumpy_data/stats/2023-01.csv")
  (convert-nginx "grumpy_data/stats/grumpy_access.log.8" "grumpy_data/stats/2022-12.csv"))
  