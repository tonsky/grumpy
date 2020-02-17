(ns grumpy.migrations
  (:require
   [grumpy.core.config :as config]
   [grumpy.core.log :as log]
   [grumpy.migrations.to-2 :as migrations.to-2]
   [grumpy.migrations.to-3 :as migrations.to-3]
   [grumpy.migrations.to-4 :as migrations.to-4]))


(defn maybe-migrate-to [version f]
  (when (< (config/get :grumpy.db/version (constantly 1)) version)
    (log/log "Migrating DB to version" version)
    (f)
    (config/set :grumpy.db/version version)))


(defn migrate! []
  (maybe-migrate-to 2 migrations.to-2/migrate!)
  (maybe-migrate-to 3 migrations.to-3/migrate!)
  #_(maybe-migrate-to 4 migrations.to-4/migrate!))


(defn -main [& args]
  (migrate!)  
  (shutdown-agents))