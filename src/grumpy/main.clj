(ns grumpy.main
  (:require
    [grumpy.core.log :as log]
    [grumpy.server :as server]
    [mount.core :as mount]))

(Thread/setDefaultUncaughtExceptionHandler
  (reify Thread$UncaughtExceptionHandler
    (uncaughtException [_ thread ex]
      (log/log "Uncaught exception on" (.getName ^Thread thread))
      (.printStackTrace ^Throwable ex))))

(defn -main [& args]
  (mount/start))
