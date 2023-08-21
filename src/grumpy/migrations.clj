(ns grumpy.migrations
  (:require
    [grumpy.core.config :as config]
    [grumpy.core.log :as log]))


(defn maybe-migrate-to [version sym]
  (when (< (config/get :grumpy.db/version (constantly 1)) version)
    (log/log "Migrating DB to version" version)
    ((requiring-resolve sym))
    (config/set :grumpy.db/version version)))


(defn migrate! []
  (maybe-migrate-to 2 'grumpy.migrations.to-2/migrate!)
  (maybe-migrate-to 3 'grumpy.migrations.to-3/migrate!)
  (maybe-migrate-to 4 'grumpy.migrations.to-4/migrate!)
  (maybe-migrate-to 5 'grumpy.migrations.to-5/migrate!))


(defn -main [& args]
  (migrate!)  
  (shutdown-agents))
