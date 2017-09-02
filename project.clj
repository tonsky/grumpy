(defproject grumpy "0.1.0-SNAPSHOT"
  :dependencies [
    [org.clojure/clojure       "1.9.0-alpha19"]
    [ring/ring-core            "1.6.2"]
    [org.immutant/web          "2.1.9"]
    ; [javax.servlet/servlet-api "2.5"]
    [compojure                 "1.6.0"]
    [rum                       "0.10.8"]
    [org.clojure/clojurescript "1.9.562" :scope "provided"]
  ]
  :global-vars {*warn-on-reflection* true}
  :main grumpy.server
  :profiles {
    :uberjar {
      :aot [grumpy.server]
      :uberjar-name "grumpy.jar"
    }
  })
