(ns grumpy.main
  (:require
    [grumpy.core.log :as log]
    [grumpy.migrations :as migrations]
    [grumpy.server :as server]
    [mount.core :as mount]))

(Thread/setDefaultUncaughtExceptionHandler
  (reify Thread$UncaughtExceptionHandler
    (uncaughtException [_ thread ex]
      (log/log "Uncaught exception on" (.getName ^Thread thread))
      (.printStackTrace ^Throwable ex))))

(defn -main [& {:as args}]
  (migrations/migrate!)
  (when-some [host (get args "--host")]
    (vswap! server/*opts assoc :host host))
  (when-some [port (get args "--port")]
    (vswap! server/*opts assoc :port (parse-long port)))
  (mount/start))
