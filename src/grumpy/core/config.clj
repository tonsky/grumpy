(ns grumpy.core.config
  (:refer-clojure :exclude [get load set])
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint])
  (:import
   [java.io File Writer]))


(defn- write-bytes [^bytes bytes]
  (.encodeToString (java.util.Base64/getEncoder) bytes))


(defn- pprint-bytes [bytes ^Writer wrt]
  (.write wrt "#bytes \"")
  (.write wrt (write-bytes bytes))
  (.write wrt "\""))


(defn- read-bytes [^String s]
  (.decode (java.util.Base64/getDecoder) s))


(defn- load []
  (let [file ^File (io/file "grumpy_data/config.edn")]
    (when (.exists file)
      (edn/read-string {:readers {'bytes read-bytes}} (slurp file)))))


(defmethod pprint/simple-dispatch (Class/forName "[B") [bytes] (pprint-bytes bytes *out*))
(defmethod print-method (Class/forName "[B") [bytes wrt] (pprint-bytes bytes wrt))


(defn- store! [config]
  (let [file ^File (io/file "grumpy_data/config.edn")]
    (.mkdirs (.getParentFile file))
    (with-open [wrt (io/writer file)]
      (pprint/pprint (into (sorted-map) config) wrt)))
  config)


(def *config (agent (load)))


(defn set [key value]
  (send *config
    (fn [_]
      (-> (load) (assoc key value) (store!))))
  value)


(defn get-optional [key]
  (clojure.core/get @*config key))


(defn get
  ([key]
    (get key #(throw (ex-info (str "Please specify " key " in grumpy_data/config.edn") {:key key}))))
  ([key value-fn]
    (or (get-optional key)
      (set key (value-fn)))))


;; force default value
(get :grumpy.server/hostname (constantly "https://grumpy.website"))


(def ^:dynamic dev?
  (= "http://localhost:8080" (get :grumpy.server/hostname)))