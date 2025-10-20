(ns grumpy.package
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.java.io :as io]
   [uberdeps.api :as uberdeps]))

(defn package []
  (binding [uberdeps/level :debug]
    (uberdeps/package
      (edn/read-string (slurp "deps.edn"))
      "target/grumpy.jar"
      {:aliases #{:uberjar}
       :exclusions [#"\.DS_Store"]})))

(defn -main [& args]
  (package)
  (shutdown-agents))
