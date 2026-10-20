(ns user
  (:require
   [clj-reload.core :as reload]
   [clojure+.core.server]
   [clojure+.error :as error]
   [clojure+.hashp :as hashp]
   [clojure+.print :as print]
   [clojure+.test :as test]
   [mount.core :as mount]))

(clojure+.error/install!
  {:trace-transform
   (fn [trace]
     (take-while #(not (#{"Compiler" "clj-reload" "clojure-sublimed"} (:ns %))) trace))})

(hashp/install!)
(print/install!)
(test/install!)

(reload/init
  {:dirs ["src" "/ws/clj-simple-stats/src"]})

(defn reload []
  (let [{:keys [loaded]} (reload/reload)]
    (str "Reloaded " (count loaded) " namespaces")))

(defn -main [& args]
  (clojure+.core.server/start-server)
  (mount/start))

(defn test-all []
  (reload/reload {:only #"clj-simple-stats\..*-test"})
  (clojure+.test/run))
