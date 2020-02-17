(ns grumpy.core.transit
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
            (fromRep [_ s] (Instant/ofEpochMilli (Long/parseLong s))))})})


(defn read-transit [is]
  (t/read (t/reader is :json reader-opts)))


(defn read-transit-str [^String s]
  (read-transit (ByteArrayInputStream. (.getBytes s "UTF-8"))))


(def ^:private writer-opts
  {:handlers
   (t/write-handler-map
     {Instant (reify WriteHandler
                (tag [_ _] "m")
                (rep [_ i] (.toEpochMilli ^Instant i))
                (stringRep [this i] (str (.rep this i)))
                (getVerboseHandler [_] nil))})})


(defn write-transit [o os]
  (t/write (t/writer os :json writer-opts) o))


(defn write-transit-bytes ^bytes [o]
  (let [os (ByteArrayOutputStream.)]
    (write-transit o os)
    (.toByteArray os)))
    

(defn write-transit-str [o]
  (String. (write-transit-bytes o) "UTF-8"))
