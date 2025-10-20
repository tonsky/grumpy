(ns user
  (:require
   [clj-reload.core :as clj-reload]
   [clojure+.core.server]
   [clojure+.error :as error]
   [clojure+.hashp :as hashp]
   [clojure+.print :as print]
   [grumpy.migrations :as migrations]
   [mount.core :as mount]))

(clojure+.error/install!
  {:trace-transform
   (fn [trace]
     (take-while #(not (#{"Compiler" "clj-reload" "clojure-sublimed"} (:ns %))) trace))})

(hashp/install!)

(print/install!)

(clj-reload/init
  {:dirs ["src"]})

(def reload
  clj-reload/reload)

(defn -main [& args]
  (clojure+.core.server/start-server)
  (migrations/migrate!)
  (when-some [*opts @(requiring-resolve 'grumpy.server/*opts)]
    (swap! *opts assoc :host "0.0.0.0"))
  (mount/start))
