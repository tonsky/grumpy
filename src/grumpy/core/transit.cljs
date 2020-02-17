(ns grumpy.core.transit
  (:require
   [cognitect.transit :as t]))


(defn read-transit-str [s]
  (t/read (t/reader :json) s))


(defn write-transit-str [o]
  (t/write (t/writer :json) o))
