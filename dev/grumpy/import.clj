(ns grumpy.import
  (:require
    [clojure.string :as str]
    [clojure.java.io :as io]
    [grumpy.core :as grumpy]
    [grumpy.authors :as authors])
  (:import
    [java.util Date]
    [org.joda.time.format DateTimeFormat]))


(def formatter (DateTimeFormat/forPattern "yyyy-MM-dd HH-mm"))


(defn parse-inst [formatter str]
  (Date. (.getMillis (.parseDateTime formatter str))))


(defn import-telegram-post [dir]
  (let [body      (slurp (io/file dir "text.txt"))
        author    (grumpy/slurp (io/file dir "author"))
        inst      (parse-inst formatter (.getName (io/file dir)))
        picture   (first (grumpy/list-files dir #".*\.(jpg|jpeg|png|gif|mp4)"))
        id        (or (grumpy/slurp (io/file dir "id"))
                      (let [id (authors/next-post-id inst)]
                        (spit (io/file dir "id") id)
                        id))]
    (authors/save-post!
      { :id id
        :body body
        :author (or author "nikitonsky")
        :created inst
        :updated inst }
      (if picture
        [{ :filename picture :tempfile (io/file dir picture) }]
        [])
      { :delete? false })))


(comment
  (doseq [dir (grumpy/list-files "grumpy_data/telegram" #"[0-9 \-]+")]
    (import-telegram-post (str "grumpy_data/telegram/" dir))))
