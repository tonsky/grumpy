(ns grumpy.core.posts
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [grumpy.core.files :as files])
  (:import
   [java.io File]))


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
