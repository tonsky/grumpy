(ns grumpy.system
  (:require
   [grumpy.db :as db]
   [grumpy.server :as server]
   [grumpy.migrations :as migrations]
   [com.stuartsierra.component :as component]))


(defn system [opts]
  (component/system-map
    :crux   (db/crux (:crux opts))
    :server (component/using
              (server/server (:server opts))
              [:crux])))


(defn -main [& {:as args}]
  (migrations/migrate!)
  (let [host (get args "--host")
        port (some-> (get args "--port") Integer/parseInt)]
    (-> (system {:server {:host host, :port port}})
        (component/start))))
