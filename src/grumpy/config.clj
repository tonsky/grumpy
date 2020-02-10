(ns grumpy.config
  (:refer-clojure :exclude [get load set])
  (:require
   [clojure.edn :as edn]
   [clojure.string :as string]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint])
  (:import
   [java.io File Writer]))


(def ^:private default
  {; :grumpy.auth/cookie-secret
   ; :grumpy.auth/forced-user
   :grumpy.db/version 1
   ; :grumpy.video/circleci-token
   ; :grumpy.telegram/token
   :grumpy.telegram/channels #{"grumpy_chat" "whining_test"}
   :grumpy.core/hostname "https://grumpy.website"})


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
    (with-open [wrt (io/writer file)]
      (pprint/pprint config wrt)))
  config)


(def *config (agent (merge default (load))))


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


(def ^:dynamic dev? (= "http://localhost:8080" (get :grumpy.core/hostname)))