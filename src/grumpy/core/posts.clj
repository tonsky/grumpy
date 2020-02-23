(ns grumpy.core.posts
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [grumpy.core.coll :as coll]
   [grumpy.core.files :as files])
  (:import
   [java.io File]
   [java.time Instant]))


(def ^:const encode-table "-0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz")


(defn encode [num len]
  (loop [num  num
         list ()
         len  len]
    (if (== 0 len)
      (str/join list)
      (recur (bit-shift-right num 6)
             (let [ch (nth encode-table (bit-and num 0x3F))]
              (conj list ch))
             (dec len)))))


(defn get-post [post-id]
  (let [path (str "grumpy_data/posts/" post-id "/post.edn")]
    (some-> (io/file path)
      (files/slurp)
      (files/read-edn-string))))


(defn post-ids []
  (->>
    (for [name (files/list-files "grumpy_data/posts")
          :let [child (io/file "grumpy_data/posts" name)]
          :when (.isDirectory child)]
      name)
    (sort)
    (reverse)))


(defn next-post-id [^Instant inst]
  (str
    (encode (quot (.toEpochMilli inst) 1000) 6)
    (encode (rand-int (* 64 64 64)) 3)))


(defn get-draft [post-id]
  (let [draft    (io/file (str "grumpy_data/drafts/" post-id "/post.edn"))
        original (io/file (str "grumpy_data/posts/" post-id "/post.edn"))]
    (cond
      (.exists draft)
        (files/read-edn-string (slurp draft))
      (.exists original)
        (do
          (files/copy-dir (io/file (str "grumpy_data/posts/" post-id)) (io/file (str "grumpy_data/drafts/" post-id)))
          (files/read-edn-string (slurp draft)))
      :else
        (do
          (.mkdirs (io/file (str "grumpy_data/drafts/" post-id "/")))
          nil))))


(defonce **agents (atom {})) ;; post-id -> agent


(defn schedule! [post-id f]
  (let [*a   (-> (swap! **agents coll/assoc-new post-id (agent nil))
               (get post-id))
        *res (promise)]
    (send *a (fn [_] (deliver *res (f)) nil))
    *res))


(defn- update-draft-impl! [post-id update-fn]
  (let [draft  (get-draft post-id)
        draft' (update-fn draft)
        dir    (str "grumpy_data/drafts/" post-id)
        file   (io/file dir "post.edn")]
    (spit (io/file file) (pr-str draft'))
    draft'))


(defn update-draft! [draft-id update-fn]
  @(schedule! draft-id #(update-draft-impl! draft-id update-fn)))