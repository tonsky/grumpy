(ns grumpy.core.posts
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [datascript.core :as d]
    [grumpy.core.config :as config]
    [grumpy.core.jobs :as jobs]
    [grumpy.core.mime :as mime]
    [grumpy.core.time :as time]
    [grumpy.db :as db])
  (:import
    [java.io File]
    [java.time Instant]))


(def ^:const encode-table
  "-0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz")


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


(defn max-id [db]
  (let [datoms (d/datoms db :avet :post/id)]
    (when-not (empty? datoms)
      (:v (first (rseq datoms))))))


(defn total-pages [db]
  (-> (max-id db) (- 1) (quot config/page-size) (+ 1)))


(defn next-id [db]
  (inc (or (max-id db) 0)))


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


(defn crosspost-media [post]
  (let [video? (= :mime.type/video (-> post :post/media mime/type))]
    (or
      (when-not video?
        (:post/media-full post))
      (:post/media post))))
