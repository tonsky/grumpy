(ns grumpy.core.files
  (:refer-clojure :exclude [slurp])
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [grumpy.core.time :as time])
  (:import
   [java.io File FilenameFilter]))


(defn slurp [source]
  (try
    (str/trim (clojure.core/slurp source))
    (catch Exception e
      nil)))


(def readers {'inst time/parse-iso-inst})


(defn read-edn-string [s]
  (edn/read-string {:readers readers} s))


(defn delete-dir [dir]
  (doseq [^File file (reverse (file-seq (io/file dir)))]
    (.delete file)))


(defn list-files
  ([dir] (seq (.list (io/file dir))))
  ([dir re]
    (seq
      (.list (io/file dir)
        (proxy [FilenameFilter] []
          (accept ^boolean [^File file ^String name]
            (boolean (re-matches re name))))))))


(defn copy-dir [^File from ^File to]
  (.mkdirs to)
  (doseq [name (list-files from)
          :let [file (io/file from name)]]
    (io/copy file (io/file to name))))