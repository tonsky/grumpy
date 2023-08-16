(ns grumpy.core.posts
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [datascript.core :as d]
    [grumpy.core.files :as files]
    [grumpy.core.jobs :as jobs]
    [grumpy.core.macros :as macros]
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
  (->> (d/datoms (db/db) :avet :post/id)
    (rseq)
    (map :v)))


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
    (let [post (d/pull (db/db) '[*] [:post/id post-id])]
      ;; TODO media
      (d/transact db/conn
        [[:db.fn/retractEntity (:db/id post)]]))))
