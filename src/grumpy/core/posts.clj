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


(defn next-post-id [^Instant inst]
  (str
    (encode (quot (.toEpochMilli inst) 1000) 6)
    (encode (rand-int (* 64 64 64)) 3)))


(defn update! [post-id update-fn]
  (jobs/linearize post-id
    (let [post  (d/entity (db/db) [:podb/id post-id])
          post' (update-fn post)
          file  (io/file "grumpy_data/posts" post-id "post.edn")]
      (spit file (pr-str post'))
      post')))


(defn delete! [post-id]
  (jobs/linearize post-id
    (files/delete-dir (str "grumpy_data/posts/" post-id))))
