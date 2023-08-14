(ns grumpy.figwheel
  (:require
    [figwheel.main.api :as fig]
    [mount.core :as mount]))

(mount/defstate figwheel
  :start
  (do
    (println "[Figwheel] Starting figwheel build")
    (fig/start
      {:mode               :serve
       :rebel-readline     false
       :cljs-devtools      false
       :helpful-classpaths false
       :open-url           false}
      {:id      "dev"
       :config  {:watch-dirs ["src"]
                 :css-dirs   ["resources/static"]}
       :options {:main       'grumpy.editor
                 :output-to  "target/resources/static/editor.js"
                 :output-dir "target/resources/static/editor"
                 :asset-path "/static/editor"}}))
  :stop
  (do
    (println "[Figwheel] Stopping figwheel build")
    (fig/stop "dev")))