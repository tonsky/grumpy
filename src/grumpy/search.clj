(ns grumpy.search
  (:require
   [clojure.string :as str]
   [datascript.core :as d]
   [grumpy.core.config :as config]
   [grumpy.core.fragments :as fragments]
   [grumpy.core.log :as log]
   [grumpy.core.url :as url]
   [grumpy.core.web :as web]
   [grumpy.db :as db]
   [mount.core :as mount]
   [rum.core :as rum])
  (:import
   [java.nio.file Path]
   [java.time Instant]
   [org.apache.lucene.analysis Analyzer]
   [org.apache.lucene.analysis.standard StandardAnalyzer]
   [org.apache.lucene.analysis.tokenattributes OffsetAttribute CharTermAttribute]
   [org.apache.lucene.document Document Field$Store LongField LongPoint TextField]
   [org.apache.lucene.index DirectoryReader IndexWriter IndexWriterConfig IndexWriterConfig$OpenMode Term]
   [org.apache.lucene.queryparser.classic QueryParser QueryParser$Operator]
   [org.apache.lucene.search IndexSearcher ScoreDoc Query]
   [org.apache.lucene.search.highlight Highlighter QueryScorer SimpleHTMLFormatter TokenSources TokenGroup]
   [org.apache.lucene.store Directory FSDirectory]))

(def paginator-size
  12)

(declare index)

(def path
  (Path/of "grumpy_data/lucene" (into-array String [])))

(mount/defstate writer
  :start
  (let [dir      (FSDirectory/open path)
        analyzer (StandardAnalyzer.)
        config   (doto (IndexWriterConfig. analyzer)
                   (.setOpenMode IndexWriterConfig$OpenMode/CREATE_OR_APPEND))
        writer   (IndexWriter. dir config)
        db       (db/db)
        eids     (map :e (d/datoms db :aevt :post/id))
        posts    (d/pull-many db [:post/id :post/body :post/author :post/created] eids)]
    (index writer posts)
    writer)
  :stop
  (.close ^IndexWriter writer))

(defn before-ns-unload []
  (mount/stop #'writer))

(defn after-ns-reload []
  (mount/start #'writer))

(defn index
  ([posts]
   (index writer posts))
  ([^IndexWriter writer posts]
   (let [t (System/currentTimeMillis)]
     (doseq [post posts]
       (let [{:post/keys [id body author ^Instant created]} post
             doc (Document.)]
         (.add doc (LongField. "id"      id Field$Store/YES))
         (.add doc (LongField. "created" (.getEpochSecond created) Field$Store/NO))
         (.add doc (TextField. "body"    body Field$Store/NO))
         (.updateDocuments writer ^Query (LongPoint/newExactQuery "id" id) ^Iterable (list doc))))
     (.flush writer)
     (.commit writer)
     (log/log "Indexed" (count posts) "posts in" (- (System/currentTimeMillis) t) "ms"))))

(defn parse-query ^Query [q analyzer]
  (let [parser (doto (QueryParser. "body" analyzer)
                 (.setDefaultOperator QueryParser$Operator/AND))]
    (.parse parser q)))

(defn search
  ([s]
   (search s {}))
  ([s {:keys [limit] :or {limit 10}}]
   (with-open [reader (DirectoryReader/open ^IndexWriter writer)]
     (let [searcher (IndexSearcher. reader)
           query    (parse-query s (StandardAnalyzer.))
           results  (.search searcher query (int limit))
           stored   (.storedFields searcher)
           hits     (.-scoreDocs results)
           total    (-> results .totalHits .value)
           res-fn   (fn []
                      (map #(-> stored (.document (.-doc ^ScoreDoc %)) (.getField "id") .numericValue) hits))]
       {:total total
        :ids   (if (<= total limit)
                 (vec (res-fn))
                 (concat
                   (vec (res-fn))
                   (lazy-seq
                     (->> (search s {:limit total}) :ids (drop limit)))))}))))

(defn highlight [^String body ^String q]
  (let [analyzer    (StandardAnalyzer.)
        query       (parse-query q analyzer)
        formatter   (SimpleHTMLFormatter. "<em>" "</em>")
        highlighter (Highlighter. formatter (QueryScorer. query))
        tokens      (TokenSources/getTokenStream "body" nil body analyzer -1)]
    (first (.getBestFragments highlighter tokens body 1000))))

(rum/defc form [q]
  [:form.search.row.middle
   [:span "Search:"]
   [:.input.grow
    [:input {:type           "search"
             :name           "q"
             :autocapitalize "none" 
             :autocomplete   "off" 
             :autocorrect    "off" 
             :autofocus      "" 
             :role           "combobox" 
             :spellcheck     "false"
             :placeholder    "Your query here"
             :value          q}]]
   [:button.btn "Find"]])

(rum/defc paginator [path query page total]
  (let [half-paginator (quot paginator-size 2)
        last-page      (-> total (- 1) (quot config/page-size) (+ 1))
        pages          (cond
                         (< (- page 1) (+ 2 half-paginator))
                         (take (+ 2 paginator-size) (range 1 (inc last-page)))
                         
                         (< (- last-page page) half-paginator)
                         (range (max 1 (- last-page paginator-size)) (inc last-page))
                         
                         :else
                         (let [from (-> page (- half-paginator) inc)
                               till (+ from paginator-size)]
                           (range from till)))]
    [:.pages
     [:.pages_title
      "Pages: "]
     [:.pages_inner
      (when (< 1 (first pages))
        (list
          [:a {:href (url/build path query)} "1"]
          [:span "..."]))
      (for [p pages]
        (if (= p page)
          [:span.selected (str p)]
          [:a {:href (url/build path (assoc query :page (str p)))} (str p)]))
      (when (< (last pages) last-page)
        [:span "..."])]]))

(rum/defc search-page [{:strs [q page]
                        :or {q "", page "1"}}]
  (web/page {:page :search}
    (list
      (form q)
      (let [q (str/replace q #"[!*?]" "")]
        (when-not (str/blank? q)
          (let [{:keys [total ids]} (search q)
                page (parse-long page)
                ids  (->> ids
                       (drop (* config/page-size (dec page)))
                       (take config/page-size))]
            (if (empty? ids)
              [:div.no-results
               "No results"]
              (let [db (db/db)]
                (list
                  [:.search-results
                   (for [id ids
                         :let [post  (d/pull db '[*] [:post/id id])
                               post' (update post :post/body highlight q)]]
                     (fragments/post post'))]
                  (paginator "/search" {:q q} page total))))))))))
