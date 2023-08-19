(ns grumpy.core.posts
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [datascript.core :as d]
    [grumpy.core.jobs :as jobs]
    [grumpy.core.time :as time]
    [grumpy.db :as db])
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


(defn post-ids []
  (let [db (db/db)]
    (->> (d/datoms db :avet :post/id)
      (rseq)
      (remove #(d/find-datom db :eavt (:e %) :post/deleted?))
      (map :v))))

(defn next-id [db]
  (let [datoms (d/datoms db :avet :post/id)
        max-id (when-not (empty? datoms)
                 (:v (first (rseq datoms))))]
    (inc (or max-id 0))))


(defn update! [post-id update-fn]
  (jobs/linearize post-id
    (let [post  (d/entity (db/db) [:podb/id post-id])
          post' (update-fn post)
          file  (io/file "grumpy_data/posts" post-id "post.edn")]
      (spit file (pr-str post'))
      post')))


(defn delete! [post-id]
  (jobs/linearize post-id
    (d/transact db/conn
      [{:post/id       post-id
        :post/updated  (time/now)
        :post/deleted? true}])))
