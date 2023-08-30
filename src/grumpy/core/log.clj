(ns grumpy.core.log
  (:require
    [grumpy.core.time :as time]))


(def lock
  (Object.))


(defn log [& args]
  (locking lock
    (apply println (time/format-log-inst) args)))