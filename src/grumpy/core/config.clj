(ns grumpy.core.config
  (:refer-clojure
    :exclude [get])
  (:require
    [clojure.string :as str]))


(defn- arg [name]
  ;; clojure.main sets *command-line-args* before loading the main ns
  (some (fn [[k v]] (when (= k name) v)) (partition 2 1 *command-line-args*)))


(defn get
  "Looks up `name` (e.g. \"data-dir\") as --data-dir command-line arg,
   then as DATA_DIR env var"
  ([name]
   (get name nil))
  ([name default]
   (or
     (not-empty (arg (str "--" name)))
     (not-empty (System/getenv (-> name str/upper-case (str/replace "-" "_"))))
     default)))


(def data-dir
  (get "data-dir" "grumpy_data"))


(def host
  (get "host" "localhost"))


(def port
  (parse-long (get "port" "8080")))


(def site-url
  (get "site-url" "https://grumpy.website"))


(def page-size
  (parse-long (get "page-size" "10")))


(def ^:dynamic dev?
  (= "http://localhost:8080" site-url))
