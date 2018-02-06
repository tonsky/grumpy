(defproject grumpy "0.1.0-SNAPSHOT"
  :dependencies [
    [org.clojure/clojure        "1.9.0"]
    [org.clojure/data.xml       "0.0.8"]
    [ring/ring-core             "1.6.3"]
    [org.immutant/web           "2.1.10"]
    [compojure                  "1.6.0"]
    [rum                        "0.11.0"]
    [org.clojure/clojurescript  "1.9.946" :scope "provided"]
    [cljs-drag-n-drop           "0.1.0"]
    [com.cognitect/transit-clj  "0.8.300"]
    [com.cognitect/transit-cljs "0.8.243"]
    [clj-http                   "3.7.0"]
    [cheshire                   "5.8.0"]
  ]

  :main grumpy.server

  :profiles {
    :uberjar {
      :aot          [grumpy.server]
      :uberjar-name "grumpy.jar"
      :auto-clean   false
    }
    :dev {
      :dependencies [
        [org.clojure/tools.nrepl "0.2.13"]
        [cider/cider-nrepl "0.16.0"]
    ]}
  }

  :aliases { "package" ["do" ["clean"] ["cljsbuild" "once" "advanced"] ["uberjar"]] }

  :plugins [
    [lein-cljsbuild "1.1.7"]
    [lein-figwheel "0.5.14"]
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
          :parallel-build       true
        } }
      { :id "advanced"
        :source-paths ["src"]
        :compiler {
          :main                 grumpy.editor
          :output-to            "resources/static/editor.js"
          :optimizations        :advanced
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
  :mirrors {
    "central" {:name "central" :url "https://repo.maven.apache.org/maven2/"}
  }
)
