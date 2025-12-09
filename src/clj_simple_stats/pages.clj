(ns clj-simple-stats.pages
  (:require
   [clojure.string :as str]
   [ring.middleware.params :as params])
  (:import
   [java.net URLEncoder]
   [java.sql DriverManager ResultSet]
   [java.time LocalDate]
   [java.time.format DateTimeFormatter]
   [java.time.temporal TemporalAdjusters]
   [org.duckdb DuckDBConnection]))

(def ^:private ^DateTimeFormatter date-time-formatter
  (DateTimeFormatter/ofPattern "MMM d"))

(def ^:private ^DateTimeFormatter year-month-formatter
  (DateTimeFormatter/ofPattern "yyyy-MM"))

(defn query [q reduce-fn init ^DuckDBConnection conn]
  (with-open [stmt (.createStatement conn)
              rs   (.executeQuery stmt q)]
    (loop [acc init]
      (if (.next rs)
        (recur (reduce-fn acc rs))
        acc))))

(defn visits-by-type+date [conn where]
  (->
    (query
      (str
        "WITH subq AS (
           SELECT type, date, MAX(mult) AS mult
           FROM stats
           WHERE " where "
           GROUP BY type, date, uniq
         )
         FROM subq
         SELECT type, date, SUM(mult) AS cnt
         GROUP BY type, date")
      (fn [acc ^ResultSet rs]
        (let [type (.getString rs 1)
              date (.getObject rs 2)
              cnt  (.getLong rs 3)]
          (update acc (keyword type) (fnil assoc! (transient {})) date cnt)))
      {} conn)
    (update-vals persistent!)))

(comment
  (clj-simple-stats.core/with-conn [conn "grumpy_data/stats.duckdb"]
    (visits-by-type+date conn "date <= '2025-12-31' AND date >= '2025-01-01'")))

(defn total-uniq [conn where]
  (query
    (str
      "WITH subq AS (
        SELECT type, MAX(mult) AS mult
        FROM stats
        WHERE " where "
        GROUP BY type, uniq
      )
      FROM subq
      SELECT type, SUM(mult) AS cnt
      GROUP BY type")
    (fn [acc ^ResultSet rs]
      (let [type (.getString rs 1)
            cnt  (.getLong rs 2)]
        (assoc acc (keyword type) cnt)))
    {} conn))

(comment
  (clj-simple-stats.core/with-conn [conn "grumpy_data/stats.duckdb"]
    (total-uniq conn "date <= '2025-12-31' AND date >= '2025-01-01'")))

(defn top-10 [conn what where]
  (->
    (query
      (str
        "WITH base_query AS (
           SELECT " what "
           FROM stats
           WHERE " where "
         ),
         top_values AS (
           FROM base_query
           SELECT
             " what " AS value,
             COUNT(*) AS count
           WHERE " what " IS NOT NULL
           GROUP BY value
           ORDER BY count DESC
         ),
         top_n AS (
           SELECT *
           FROM top_values
           ORDER BY count DESC
           LIMIT 10
         ),
         others AS (
           SELECT
             NULL AS value,
             COUNT(*) AS count
           FROM base_query
           WHERE " what " IS NOT NULL
           AND " what " NOT IN (SELECT value FROM top_n)
         )
         FROM top_n
         UNION ALL
         FROM others
         WHERE count > 0")
      (fn [acc ^ResultSet rs]
        (conj! acc [(.getString rs 1) (.getLong rs 2)]))
      (transient []) conn)
    persistent!))

(defn top-10-uniq [conn what where]
  (->
    (query
      (str
        "WITH base_query AS (
           SELECT ANY_VALUE(" what ") AS " what ", MAX(mult) AS mult
           FROM stats
           WHERE " where "
           GROUP BY uniq
         ),
         top_values AS (
           FROM base_query
           SELECT
             " what " AS value,
             SUM(mult) AS count
           WHERE " what " IS NOT NULL
           GROUP BY value
           ORDER BY count DESC
         ),
         top_n AS (
           SELECT *
           FROM top_values
           ORDER BY count DESC
           LIMIT 10
         ),
         others AS (
           SELECT
             NULL AS value,
             SUM(mult) AS count
           FROM base_query
           WHERE " what " IS NOT NULL
           AND " what " NOT IN (SELECT value FROM top_n)
         )
         FROM top_n
         UNION ALL
         FROM others
         WHERE count > 0")
      (fn [acc ^ResultSet rs]
        (conj! acc [(.getString rs 1) (.getLong rs 2)]))
      (transient []) conn)
    persistent!))

(comment
  (clj-simple-stats.core/with-conn [conn "grumpy_data/stats.duckdb"]
    #_(top-10 conn "path" "type = 'browser'")
    (top-10 conn "query" "type = 'browser'")
    #_(top-10 conn "query" "path = '/search' AND type = 'browser'")))

(def styles
  ":root { --padding-body: 20px; --padding-graph_outer: 10px; --width-graph_legend: 20px; }
   body { margin: 0; padding: 20px var(--padding-body); background: #EDEDF2; }
   a { color: inherit; text-decoration-color: #00000040; }
   a:hover { text-decoration-color: #000000; }

   .filters { display: flex; gap: 3px; }
   .filter { display: flex; margin-left: 0px; }
   .filter { display: inline-block; padding: 3px 6px; text-decoration: none; font-size: 13px; }
   .filter.in { background: #DDDDE2; }
   a.filter:hover,
   a.filter.in:hover { background: #CCCCD4; }
   div.filter { background: #DDDDE2; }
   div.filter > a { display: inline-block; padding: 3px 6px; margin: -3px -6px -3px 0; text-decoration: none; }
   div.filter > a:hover { background: #CCCCD4; }

   h1 { font-size: 16px; margin: 20px 0 8px 0; }
   .graph_outer { background: #FFF; border-radius: 6px; padding: 10px var(--padding-graph_outer) 0; display: flex; width: max-content; max-width: calc(100vw - var(--padding-body) * 2); position: relative; }
   .graph_hover { font-size: 10px; font-feature-settings: 'tnum' 1; color: #a35249; position: absolute; left: var(--padding-graph_outer); top: 10px; background: #ffe1dc; padding: 2px 6px; border-radius: 2px; }
   .graph_scroll { max-width: calc(100vw - var(--padding-body) * 2 - var(--padding-graph_outer) * 2 - var(--width-graph_legend)); overflow-x: auto; padding-bottom: 30px; margin-bottom: -20px; }
   .graph { display: block; }
   .graph > g > rect { fill: #d6f1ff; }
   .graph > g > line { stroke: #0177a1; stroke-width: 2; }
   .graph > g:hover > rect { fill: #ffe1dc; }
   .graph > g:hover > line { stroke: #a35249; }
   .graph > line.hrz  { stroke: #0000000B; stroke-width: 1; }
   .graph > line.date { stroke: #00000020; stroke-width: 1; }
   .graph > line.today { stroke: #FF000030; stroke-width: 1; }
   .graph > a { font-size: 10px; fill: #00000080; }
   .graph > a:hover { fill: #000000; }
   .graph_legend { width: var(--width-graph_legend); }
   .graph_legend > text { font-size: 10px; fill: #00000070; }

   .tables { display: flex; flex-direction: row; flex-wrap: wrap; column-gap: 20px; }
   .table_outer { }

   table { font-size: 13px; font-feature-settings: 'tnum' 1; background: #FFF; padding: 6px 10px; border-radius: 6px; border-spacing: 4px; width: 400px; }
   th, td { padding: 0; }
   th { text-align: left; font-weight: normal; width: 245px; position: relative; }
   th > div { height: 20px; background-color: #B9E5FE; border-radius: 2px; }
   th > span, th > a { height: 20px; line-height: 20px; position: absolute; top: 0; left: 4px; width: calc(250px - 4px); overflow: hidden; text-overflow: ellipsis;  }
   td.f { text-align: left; width: 15px; }
   td.f > a { opacity: 0.25; text-decoration: none; }
   td.f > a:hover { opacity: 1; }
   td { text-align: right; width: 50px; }
   .pct { color: #00000070; }")

(def script
  "window.addEventListener('load', () => {
     const scrollables = document.querySelectorAll('.graph_scroll');

     scrollables.forEach((el) => {
       el.scrollLeft = el.scrollWidth;
     });

     scrollables.forEach((el) => {
       el.addEventListener('scroll', () => {
         const scrollLeft = el.scrollLeft;
         scrollables.forEach((other) => {
           if (other !== el) {
             other.scrollLeft = scrollLeft;
           }
         });
       });
     });

     const graphs = document.querySelectorAll('.graph');

     graphs.forEach((graph) => {
       const graphOuter = graph.closest('.graph_outer');
       const graphHover = graphOuter.querySelector('.graph_hover');

       graph.addEventListener('mouseover', (e) => {
         if (e.target.parentNode.tagName === 'g') {
           const g = e.target.parentNode;
           const value = g.getAttribute('data-v');
           const date = g.getAttribute('data-d');
           if (value && date) {
             graphHover.style.display = 'block';
             graphHover.textContent = date + ': ' + value;
           }
         } else {
           graphHover.style.display = 'none';
         }
       });

       graph.addEventListener('mouseleave', () => {
         graphHover.style.display = 'none';
       });
     });
   });")

(defn format-num [n]
  (->
    (cond
      (>= n 10000000) (format "%1.0fM" (/ n 1000000.0))
      (>= n  1000000) (format "%1.1fM" (/ n 1000000.0))
      (>= n    10000) (format "%1.0fK" (/ n    1000.0))
      (>= n     1000) (format "%1.1fK" (/ n    1000.0))
      :else           (str n))
    (str/replace ".0" "")))

(defn round-to [n m]
  (-> n
    (- 1)
    (/ m)
    Math/floor
    (+ 1)
    (* m)
    int))

(def bar-w
  3)

(def graph-h
  100)

(defn encode-uri-component [s]
  (-> (URLEncoder/encode (str s) "UTF-8")
    (str/replace #"\+"   "%20")
    (str/replace #"\%21" "!")
    (str/replace #"\%27" "'")
    (str/replace #"\%28" "(")
    (str/replace #"\%29" ")")
    (str/replace #"\%7E" "~")))

(defn querystring [params]
  (str/join "&"
    (map
      (fn [[k v]]
        (str (name k) "=" (encode-uri-component v)))
      params)))

(defn page [conn req]
  (let [params (-> req params/params-request :query-params)
        {:strs [from to]} params
        today (LocalDate/now)]
    (if (or (nil? from) (nil? to))
      (let [from (.with (LocalDate/now) (TemporalAdjusters/firstDayOfYear))
            to   today]
        {:status  302
         :headers {"Location" (str "?" (querystring (assoc params "from" from "to" to)))}})
      (let [from-date (LocalDate/parse from)
            to-date   (LocalDate/parse to)
            where     (str/join " AND "
                        (concat
                          [(str "date >= '" from "'")
                           (str "date <= '" to "'")]
                          (for [[k v] (dissoc params "from" "to")]
                            (str k " = '" (str/replace (str v) "'" "\\'") "'"))))
            sb        (StringBuilder.)
            append    #(do
                         (doseq [s %&]
                           (.append sb (str s)))
                         (.append sb "\n"))]
        (append "<!DOCTYPE html>")
        (append "<html>")
        (append "<head>")
        (append "<meta charset=\"utf-8\">")
        (append "<link rel='icon' href='" (:uri req) "/favicon.ico' sizes='32x32'>")
        (append "<style>" styles "</style>")
        (append "<script>" script "</script>")
        (append "</head>")
        (append "<body>")

        ;; filters
        (let [{:keys [^LocalDate min-date
                      ^LocalDate max-date]} (query "SELECT min(date), max(date) FROM stats"
                                              (fn [acc ^ResultSet rs]
                                                (assoc acc
                                                  :min-date (.getObject rs 1)
                                                  :max-date (.getObject rs 2)))
                                              {} conn)
              min-date       (or min-date (.with (LocalDate/now) (TemporalAdjusters/firstDayOfYear)))
              max-date       (or max-date (.with (LocalDate/now) (TemporalAdjusters/lastDayOfYear)))
              min-year       (.getYear min-date)
              max-year       (.getYear max-date)]
          (append "<div class=filters>")

          ;; years
          (append "<a class=filter href='?" (querystring (assoc params
                                                           "from" min-date
                                                           "to" max-date)) "'>All</a>")
          (doseq [^long year (range min-year (inc max-year))
                  :let [qs (querystring (assoc params
                                          "from" (str year "-01-01")
                                          "to"   (str year "-12-31")))]]
            (append "<a href='?" qs "' class='filter")
            (when-not (or
                        (< (.getYear to-date) year)
                        (> (.getYear from-date) year))
              (append " in"))
            (append "'>" year "</a>"))

          ;; other params
          (doseq [[k v] (dissoc params "from" "to")]
            (append "<div class=filter>" k ": " v)
            (append "<a href='?" (querystring (dissoc params k)) "'>×</a>")
            (append "</div>")) ;; .filter

          (append "</div>")) ;; .filters

        ;; timelines
        (let [data     (visits-by-type+date conn where)
              totals   (total-uniq conn where)
              max-val  (->> data
                         (mapcat (fn [[_type date->cnt]] (vals date->cnt)))
                         (reduce max 1))
              max-val  (cond
                         (>= max-val 200000) (round-to max-val 100000)
                         (>= max-val  20000) (round-to max-val  10000)
                         (>= max-val   2000) (round-to max-val   1000)
                         (>= max-val    100) (round-to max-val    100)
                         :else                                    100)
              dates    (stream-seq! (LocalDate/.datesUntil from-date (.plusDays to-date 1)))
              graph-w  (* (count dates) bar-w)

              bar-h    #(-> % (* graph-h) (/ max-val) int)
              hrz-step (cond
                         (>= max-val 600000) 200000
                         (>= max-val 300000) 100000
                         (>= max-val 100000)  50000

                         (>= max-val  60000)  20000
                         (>= max-val  30000)  10000
                         (>= max-val  10000)   5000

                         (>= max-val   6000)   2000
                         (>= max-val   3000)   1000
                         (>= max-val   1000)    500

                         (>= max-val    600)    200
                         (>= max-val    300)    100
                         (>= max-val    100)     50

                         (>= max-val     60)     20
                         :else                   10)]

          (doseq [[type title] [[:browser "Unique visitors"]
                                [:feed "RSS Readers"]
                                [:bot "Bots"]]
                  :let [date->cnt (get data type)]
                  :when (not (empty? date->cnt))]
            (append (format "<h1>%s: %,d</h1>" title (get totals type)))
            (append "<div class=graph_outer>")

            ;; .graph
            (append "<div class=graph_hover style='display: none'></div>")
            (append "<div class=graph_scroll>")
            (append "<svg class=graph width=" graph-w " height=" (+ graph-h 30) ">")
            (doseq [[idx ^LocalDate date] (map vector (range) dates)
                    :let [val (get date->cnt date)]]
              ;; graph bar
              (when val
                (let [bar-h  (bar-h val)
                      data-v (format "%,d" val)
                      data-d (.format date-time-formatter date)
                      x      (* idx bar-w)
                      y      (- graph-h bar-h -10)]
                  (append "<g data-v='" data-v "' data-d='" data-d "'>")
                  (append "<rect x=" x " y=" (- y 2) " width=" bar-w " height=" (+ bar-h 2) " />")
                  (append "<line x1=" x " y1=" (- y 1) " x2=" (+ x bar-w) " y2=" (- y 1) " />")
                  (append "</g>")))
              ;; month label
              (when (= 1 (.getDayOfMonth date))
                (let [month-end (.with date (TemporalAdjusters/lastDayOfMonth))
                      qs        (querystring (assoc params "from" date "to" month-end))
                      x         (* idx bar-w)]
                  (append "<line class=date x1=" x " y1=" (+ 12 graph-h) " x2=" x " y2=" (+ 20 graph-h) " />")
                  (append "<a href='?" qs "'>")
                  (append "<text x=" x " y=" (+ 30 graph-h) ">" (.format year-month-formatter date) "</text>")
                  (append "</a>")))

              ;; today
              (when (= today date)
                (append "<line class=today x1=" (* (+ idx 0.5) bar-w) " y1=0 x2=" (* (+ idx 0.5) bar-w) " y2=" (+ 20 graph-h) " />")))
            ;; horizontal lines
            (doseq [val (range 0 (inc max-val) hrz-step)
                    :let [bar-h (bar-h val)]]
              (append "<line class=hrz x1=0 y1=" (- graph-h bar-h -10) " x2=" graph-w " y2=" (- graph-h bar-h -10) " />"))
            (append "</svg>") ;; .graph
            (append "</div>") ;; .graph_scroll

            ;;.graph_legend
            (append "<svg class=graph_legend height=" (+ graph-h 30) ">")
            (doseq [val (range 0 (inc max-val) hrz-step)
                    :let [bar-h (bar-h val)]]
              (append "<text x=20 y=" (- graph-h bar-h -13) " text-anchor=end>" (format-num val) "</text>"))
            (append "</svg>") ;; .graph_legend

            (append "</div>"))) ;; .graph_outer

        ;; top Ns
        (let [tbl (fn [title data & [opts]]
                    (when-not (empty? data)
                      (append "<div class=table_outer>")
                      (append "<h1>" title "</h1>")
                      (append "<table>")
                      (doseq [:let [{:keys [param href-fn]} opts
                                    total (max 1 (transduce (map second) + 0 data))]
                              [value count] data
                              :let [percent     (* 100.0 (/ count total))
                                    percent-str (if (< percent 2.0)
                                                  (format "%.1f%%" percent)
                                                  (format "%.0f%%" percent))]
                              :when (pos? count)]
                        (append "<tr>")
                        (append "<td class=f>")
                        (when (and param value)
                          (append "<a href='?" (querystring (assoc params param value)) "' title='Filter by " param " = " value "'>🔍</a>"))
                        (append "</td>")
                        (append "<th>")
                        (append "<div style='width: " percent-str "'" (when (nil? value) " class=other") "></div>")
                        (if (and href-fn value)
                          (append "<a href='" (href-fn value) "' title='" value "' target=_blank>" value "</a>")
                          (append "<span title='" (or value "Others") "'>" (or value "Others") "</span>"))
                        (append "</th>")
                        (append "<td>" (format-num count) "</td>")
                        (append "<td class='pct'>" percent-str "</td>")
                        (append "</tr>"))
                      (append "</table>")
                      (append "</div>")))]
          (append "<div class=tables>")
          (tbl "Paths"       (top-10      conn "path"       (str "type = 'browser' AND " where)) {:param "path", :href-fn identity})
          (tbl "Queries"     (top-10      conn "query"      (str "type = 'browser' AND " where)) {:param "query"})
          (tbl "Referrers"   (top-10      conn "ref_domain" (str "type = 'browser' AND " where)) {:param "ref_domain", :href-fn #(str "https://" %)})
          (tbl "Browsers"    (top-10-uniq conn "agent"      (str "type = 'browser' AND " where)) {:param "agent"})
          (tbl "OSes"        (top-10-uniq conn "os"         (str "type = 'browser' AND " where)) {:param "os"})
          (tbl "RSS Readers" (top-10-uniq conn "agent"      (str "type = 'feed'    AND " where)) {:param "agent"})
          (tbl "Bots"        (top-10-uniq conn "agent"      (str "type = 'bot'     AND " where)) {:param "agent"})
          (append "</div>"))

        (append "</body>")
        (append "</html>")
        {:status  200
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body    (.toString sb)}))))
