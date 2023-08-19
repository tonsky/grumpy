(ns grumpy.core.transit
  (:require
   [cognitect.transit :as t]))

(defn read-string [s]
  (t/read (t/reader :json) s))

(defn write-string [o]
  (t/write (t/writer :json) o))
