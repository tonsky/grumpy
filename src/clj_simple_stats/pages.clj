(ns clj-simple-stats.pages
  (:require
   [clojure.string :as str])
  (:import
   [java.sql DriverManager ResultSet]
   [java.time LocalDate]
   [java.time.format DateTimeFormatter]
   [org.duckdb DuckDBConnection]))

(def ^:private ^DateTimeFormatter month-formatter
  (DateTimeFormatter/ofPattern "yyyy-MM"))

(defn query [query reduce-fn init ^DuckDBConnection conn]
  (with-open [stmt (.createStatement conn)
              rs   (.executeQuery stmt query)]
    (loop [acc init]
      (if (.next rs)
        (recur (reduce-fn acc rs))
        acc))))

(defn visits-by-type+date [^DuckDBConnection conn]
  (->
    (query
      "SELECT type, date, sum(mult) AS cnt
     FROM (
       SELECT DISTINCT ON (type, date, uniq) type, date, mult
       FROM stats
     ) subq
     GROUP BY type, date
     ORDER BY type, date"
      (fn [acc ^ResultSet rs]
        (let [type (.getString rs 1)
              date (.getObject rs 2)
              cnt  (.getLong rs 3)]
          (update acc (keyword type) (fnil assoc! (transient {})) date cnt)))
      {} conn)
    (update-vals persistent!)))

(comment
  (clj-simple-stats.core/with-conn [conn "grumpy_data/stats.duckdb"]
    (visits-by-type+date conn)))

(defn top-10 [^DuckDBConnection conn what where]
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
             count(*) AS count
           WHERE " what " IS NOT NULL
           GROUP BY value
           ORDER BY count desc
           ),
         top_n AS (
           SELECT *
           FROM top_values
           ORDER BY count desc
           LIMIT 10
         ),
         others AS (
           SELECT
             NULL AS value,
             count(*) AS count
           FROM base_query
           WHERE " what " IS NOT NULL
           AND " what " NOT IN (SELECT VALUE FROM top_n)
         )
         FROM top_n
         UNION ALL
         FROM others")
      (fn [acc ^ResultSet rs]
        (conj! acc [(.getString rs 1) (.getLong rs 2)]))
      (transient []) conn)
    persistent!))

(comment
  (clj-simple-stats.core/with-conn [conn "grumpy_data/stats.duckdb"]
    #_(top-10 conn "path" "type = 'browser'")
    (top-10 conn "query" "type = 'browser'")
    #_(top-10 conn "query" "path = '/search' AND type = 'browser'")))

(def styles "
  body { margin: 0; padding: 0 20px 20px 20px; background: #EDEDF2; }
  h1 { font-size: 16px; margin: 20px 0 8px 0; }
  .graph_outer { background: #FFF; border-radius: 6px; padding: 10px 10px 0 10px; display: flex; }
  .graph_scroll { max-width: calc(100% - 20px); overflow-x: auto; padding-bottom: 30px; margin-bottom: -20px; }
  .graph { display: block; }
  .graph > rect { fill: #d6f1ff; }
  .graph > line { stroke: #0177a1; stroke-width: 2; }
  .graph > line.hrz  { stroke: #0000000B; stroke-width: 1; }
  .graph > line.date { stroke: #00000020; stroke-width: 1; }
  .graph > text { font-size: 10px; fill: #00000080; }
  .graph_legend { width: 20px; }
  .graph_legend > text { font-size: 10px; fill: #00000070; }

  .tables { display: flex; flex-direction: row; flex-wrap: wrap; column-gap: 20px; }
  .table_outer { }

  table { font-size: 13px; font-feature-settings: 'tnum' 1; background: #FFF; padding: 6px 10px; border-radius: 6px; border-spacing: 4px; width: 400px; }
  th, td { padding: 0; }
  th { text-align: left; font-weight: normal; width: 250px; position: relative; }
  th > div { height: 20px; background-color: #B9E5FE; border-radius: 2px; }
  th > div.other { background-color: #E5E5E5; }
  th > span { height: 20px; line-height: 20px; position: absolute; top: 0; left: 4px; width: calc(250px - 4px); overflow: hidden; text-overflow: ellipsis; }
  td { text-align: right; width: 75px; }
  .pct { color: #00000070; }
")

(def script "
  window.addEventListener('load', () => {
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
  });
")

(defn min+ [a b]
  (let [c (compare a b)]
    (if (pos? c)
      b
      a)))

(defn format-num [n]
  (->
    (cond
      (>= n 10000000) (format "%1.0fM"   (/ n 1000000.0))
      (>= n  1000000) (format "%1.1fM" (/ n 1000000.0))
      (>= n    10000) (format "%1.0fK"   (/ n    1000.0))
      (>= n     1000) (format "%1.1fK" (/ n    1000.0))
      :else           (str n))
    (str/replace ".0" "")))

(comment
  (format-num 1000000)
  (format-num 1500000)
  (format-num 22000)
  (format-num 1500)
  (format-num 1000)
  (format-num 500)
  (format-num 1))

(defn round-to [n m]
  (-> n
    (- 1)
    (/ m)
    Math/floor
    (+ 1)
    (* m)
    int))

(comment
  (round-to 70001 1000))

(def bar-w
  3)

(def graph-h
  100)

(defn page-all [conn req]
  (let [sb       (StringBuilder.)
        append   #(do
                    (doseq [s %&]
                      (.append sb (str s)))
                    (.append sb "\n"))]
    (append "<!DOCTYPE html>")
    (append "<html>")
    (append "<head>")
    (append "<meta charset=\"utf-8\">")
    (append "<style>" styles "</style>")
    (append "<script>" script "</script>")
    (append "</head>")
    (append "<body>")

    (let [data     (visits-by-type+date conn)
          max-val  (->> data
                     (mapcat (fn [[_type date->cnt]] (vals date->cnt)))
                     (reduce max 1))
          max-val  (cond
                     (>= max-val 200000) (round-to max-val 100000)
                     (>= max-val  20000) (round-to max-val  10000)
                     (>= max-val   2000) (round-to max-val   1000)
                     (>= max-val    200) (round-to max-val    100)
                     :else                                    100)
          max-date (LocalDate/now)
          min-date (->> data
                     (mapcat (fn [[_type date->cnt]] (keys date->cnt)))
                     (reduce min+ max-date))
          dates    (stream-seq! (LocalDate/.datesUntil min-date (.plusDays max-date 1)))
          graph-w  (* (count dates) bar-w)
          bar-h    #(-> % (* graph-h) (/ max-val) int)
          hrz-step (cond
                     (>= max-val 200000) 100000
                     (>= max-val 100000)  20000
                     (>= max-val  20000)  10000
                     (>= max-val  10000)   2000
                     (>= max-val   2000)   1000
                     (>= max-val   1000)    200
                     (>= max-val    200)    100
                     (>= max-val    100)     20
                     :else                   10)]

      (doseq [[type title] [[:browser "Browsers"]
                            [:feed "RSS Readers"]
                            [:bot "Bots"]]
              :let [date->cnt (get data type)]]
        (append "<h1>" title "</h1>")
        (append "<div class=graph_outer>")

        ;; .graph
        (append "<div class=graph_scroll>")
        (append "<svg class=graph width=" graph-w " height=" (+ graph-h 30) ">")
        (doseq [[idx ^LocalDate date] (map vector (range) dates)
                :let [val (get date->cnt date)]
                :when val
                :let [bar-h (bar-h val)]]
          ;; graph bar
          (append "<rect x=" (* idx bar-w) " y=" (- graph-h bar-h -10) " width=" bar-w " height=" bar-h " />")
          (append "<line x1=" (* idx bar-w) " y1=" (- graph-h bar-h -10) " x2=" (* (+ idx 1) bar-w) " y2=" (- graph-h bar-h -10) " />")
          ;; month label
          (when (= 1 (.getDayOfMonth date))
            (append "<line class=date x1=" (* (+ idx 0.5) bar-w) " y1=" (+ 12 graph-h) " x2=" (* (+ idx 0.5) bar-w) " y2=" (+ 20 graph-h) " />")
            (append "<text x=" (* idx bar-w) " y=" (+ 30 graph-h) ">" (.format month-formatter date) "</text>")))
        ;; horizontal lines
        (doseq [val (range 0 (inc max-val) hrz-step)
                :let [bar-h (bar-h val)]]
          (append "<line class=hrz x1=0 y1=" (- graph-h bar-h -10) " x2=" graph-w " y2=" (- graph-h bar-h -10) " />"))
        (append "</svg>") ;; .graph
        (append "</div>") ;; .graph_scroll

        ;;.graph_legend
        (append "<svg class=graph_legend width=30 height=" (+ graph-h 30) ">")
        (doseq [val (range 0 (inc max-val) hrz-step)
                :let [bar-h (bar-h val)]]
          (append "<text x=20 y=" (- graph-h bar-h -13) " text-anchor=end>" (format-num val) "</text>"))
        (append "</svg>") ;; .graph_legend

        (append "</div>"))) ;; .graph_outer

    (let [append-table (fn [title data]
                         (append "<div class=table_outer>")
                         (append "<h1>" title "</h1>")
                         (append "<table>")
                         (doseq [:let [total (max 1 (transduce (map second) + 0 data))]
                                 [value count] data
                                 :let [percent (format "%.2f%%" (* 100.0 (/ count total)))]]
                           (append "<tr>")
                           (append "<th><div style='width: " percent "'" (when (nil? value) " class=other") "></div><span title='" (or value "Others") "'>" (or value "Others") "</span></th>")
                           (append "<td>" (format-num count) "</td>")
                           (append "<td class='pct'>" percent "</td>")
                           (append "</tr>"))
                         (append "</table>")
                         (append "</div>"))]
      (append "<div class=tables>")
      (append-table "Pages"       (top-10 conn "path"     "type = 'browser'"))
      (append-table "Queries"     (top-10 conn "query"    "type = 'browser'"))
      (append-table "Referrers"   (top-10 conn "referrer" "type = 'browser'"))
      (append-table "Browsers"    (top-10 conn "agent"    "type = 'browser'"))
      (append-table "RSS Readers" (top-10 conn "agent"    "type = 'feed'"))
      (append-table "Bots"        (top-10 conn "agent"    "type = 'bot'"))
      (append "</div>"))

    (append "</body>")
    (append "</html>")
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body    (.toString sb)}))

(defn page-month [conn req month]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body    (str "page-month: " month)})

(defn page-path [conn req path]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body    (str "page-path: " path)})
