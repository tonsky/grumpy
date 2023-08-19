(ns grumpy.core.transit
  (:refer-clojure :exclude [read read-string write])
  (:require
   [cognitect.transit :as t])
  (:import
   [java.io ByteArrayInputStream ByteArrayOutputStream]
   [java.time Instant]
   [com.cognitect.transit WriteHandler ReadHandler])) 

(def ^:private reader-opts
  {:handlers
   (t/read-handler-map
     {"m" (reify ReadHandler
            (fromRep [_ s]
              (cond-> s
                (string? s) parse-long
                true        Instant/ofEpochMilli)))})})

(defn read [is type]
  (t/read (t/reader is type reader-opts)))

(defn read-bytes [^bytes bs type]
  (read (ByteArrayInputStream. bs) type))

(defn read-string [^String s]
  (read-bytes (.getBytes s "UTF-8") :json))

(def ^:private writer-opts
  {:handlers
   (t/write-handler-map
     {Instant (reify WriteHandler
                (tag [_ _] "m")
                (rep [_ i] (.toEpochMilli ^Instant i))
                (stringRep [this i] (str (.rep this i)))
                (getVerboseHandler [_] nil))})})

(defn write [o os type]
  (t/write (t/writer os type writer-opts) o))

(defn write-bytes ^bytes [o type]
  (let [os (ByteArrayOutputStream.)]
    (write o os type)
    (.toByteArray os)))
    
(defn write-string [o]
  (String. (write-bytes o :json) "UTF-8"))
