(defproject grumpy "0.1.0-SNAPSHOT"
  :dependencies [
    [org.clojure/clojure       "1.9.0-alpha19"]
    [ring/ring-core            "1.6.2"]
    [org.immutant/web          "2.1.9"]
    [compojure                 "1.6.0"]
    [rum                       "0.10.8"]
    [org.clojure/clojurescript "1.9.908" :scope "provided"]
  ]
  
  :global-vars {*warn-on-reflection* true}
  :jvm-opts ["-Xmx3500m"]
  
  :main grumpy.server

  :profiles {
    :uberjar {
      :aot          [grumpy.server]
      :uberjar-name "grumpy.jar"
      :auto-clean   false
    }
    :dev {
      :dependencies [
        [org.clojure/tools.nrepl "0.2.12"]
        [cider/cider-nrepl "0.15.1"]
    ]}
  }
  
  :aliases { "package" ["do" ["clean"] ["cljsbuild" "once" "advanced"] ["uberjar"]] }

  :plugins [
    [lein-cljsbuild "1.1.6"]
    [lein-figwheel "0.5.13"]
  ]

  :figwheel {
    :http-server-root "static"
    :server-port      8080
    :ring-handler     grumpy.server/app
    :css-dirs         ["resources/static"]
    :repl             false
    :nrepl-port       7888
    :nrepl-middleware ["cider.nrepl/cider-middleware"]
  }

  :cljsbuild {
    :builds [
      { :id "none"
        :source-paths ["src"]
        :figwheel { :on-jsload "grumpy.editor/refresh" }
        :compiler {
          :main                 grumpy.editor
          :output-to            "resources/static/editor.js"
          :output-dir           "resources/static/editor-none"
          :asset-path           "/static/editor-none"
          :source-map           true
          ; :source-map-timestamp true
          :source-map-path      "/static/editor-none"
          :optimizations        :none
          :compiler-stats       true
          :parallel-build       true
        } }
      { :id "advanced"
        :source-paths ["src"]
        :compiler {
          :main                 grumpy.editor
          :output-to            "resources/static/editor.js"
          :optimizations        :advanced
          :pretty-print         true
          :compiler-stats       true
          :output-dir           "resources/static/editor-advanced"
          :source-map           "resources/static/editor.js.map"
          :source-map-path      "/static/editor-advanced"
          ; :source-map-timestamp true
          :parallel-build       true
        } }
  ]}
  :clean-targets
  ^{:protect false} [ "target"
                      "resources/static/editor-none"
                      "resources/static/editor-advanced"
                      "resources/static/editor.js"
                      "resources/static/editor.js.map" ]
)