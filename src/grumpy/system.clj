(ns grumpy.system
  (:require
   [com.stuartsierra.component :as component]
   [grumpy.db :as db]
   [grumpy.server :as server]))


(defn system [opts]
  (component/system-map
    :server (server/server (:server opts))))


(defn -main [& {:as args}]
  (db/migrate! 2 db/update-1->2)
  (db/migrate! 3 db/update-2->3)
  (let [host (get args "--host")
        port (some-> (get args "--port") Integer/parseInt)]
    (-> (system {:server {:host host, :port port}})
        (component/start))))
