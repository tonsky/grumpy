(ns grumpy.core.log)


(defn log [& args]
  (apply println args))
