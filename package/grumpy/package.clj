(ns grumpy.package
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.java.io :as io]
   [cljs.build.api :as cljs]
   [uberdeps.api :as uberdeps]))


(defn compile-cljs []
  (let [t0 (System/currentTimeMillis)]
    (println "[package] Compiling target/uberjar/static/editor.js...")
    (cljs/build (io/file "src")
      {:main            'grumpy.editor
       :output-to       "target/uberjar/static/editor.js"
       :output-dir      "target/resources/static/editor"
       :asset-path      "/static/editor"
       :optimizations   :advanced
       :elide-asserts   true
       :closure-defines {"goog.DEBUG" false}
       :parallel-build  true})
    (println "[package] Compiled target/uberjar/static/editor.js in" (- (System/currentTimeMillis) t0) "ms")))


(defn package []
  (binding [uberdeps/exclusions (into uberdeps/exclusions
                                  [#"\.DS_Store"
                                   #".*\.cljs"
                                   #"cljsjs/.*"
                                   #"META-INF/maven/cljsjs/.*"])
            uberdeps/level :debug]
    (uberdeps/package (edn/read-string (slurp "deps.edn")) "target/grumpy.jar" {:aliases #{:uberjar}})))


(defn -main [& args]
  (compile-cljs)
  (package)
  (shutdown-agents))