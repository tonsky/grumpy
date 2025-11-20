(ns clj-simple-stats.pages
  (:import
   [java.sql DriverManager]))

(defn page-all [req db-path]
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
    (append ".by_day .day:hover > div { opacity: 0.5; }")

    (append ".r { background-color: #C1131F40; }")
    (append ".u { background-color: #01B5D8; }")
    (append ".f { background-color: #0A9496; }")

    (append "</style>")
    (append "</head><body>")

    (with-open [conn (DriverManager/getConnection (str "jdbc:duckdb:" db-path))]
      #_(let [data     (with-open [stmt     (.createStatement conn)
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
                                            "select date::VARCHAR as date, type, sum(mult) as cnt
                                             from (
                                               select distinct on (date, type, uniq) date, type, uniq, mult
                                               from stats
                                             ) subq
                                             group by date, type
                                             order by date, type")]
                       (loop [acc {}]
                         (if (.next rs)
                           (let [date (.getString rs "date")
                                 type (.getString rs "type")
                                 cnt  (.getLong rs "cnt")]
                             (recur (update acc date assoc (keyword type) cnt)))
                           (reduce
                             (fn [acc [k v]]
                               (conj acc (assoc v :date k)))
                             []
                             (sort-by first acc)))))
            max-val  (reduce (fn [acc m]
                               (max acc (reduce + 0 (vals (dissoc m :date))))) 1 data)
            by-month (group-by #(subs (:date %) 0 7) data)
            months   (sort (keys by-month))]
        (append "<div class=\"container\">")
        (append "<div class=\"stats by_day\">")
        (doseq [month months]
          (let [days (sort-by :date (get by-month month))]
            (append "<div class=\"month\">")
            (append "<div class=\"bars\">")
            (doseq [{:keys [date feed browser bot]} days]
              (let [f-height (int (* 200 (/ (or feed 0) max-val)))
                    u-height (int (* 200 (/ (or browser 0) max-val)))
                    r-height (int (* 200 (/ (or bot 0) max-val)))]
                (append "<div class=\"day\" title=\"" date "\">")
                (append "<div class=\"r\" style=\"height: " r-height "px\"></div>")
                (append "<div class=\"u\" style=\"height: " u-height "px\"></div>")
                (append "<div class=\"f\" style=\"height: " f-height "px\"></div>")
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

(defn page-month [req month]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body    (str "page-month: " month)})

(defn page-path [req path]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body    (str "page-path: " path)})
