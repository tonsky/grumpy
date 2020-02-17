(ns grumpy.core.log
  (:require
   #?(:clj [grumpy.core.time :as time])))


(defn log [& args]
  (apply println #?(:clj (time/format-log-inst)) args))