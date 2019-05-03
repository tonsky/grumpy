(ns grumpy.figwheel
  (:require
   [com.stuartsierra.component :as component]
   [cljs.stacktrace]
   [figwheel.main.api :as fig]))


(defrecord Figwheel []
  component/Lifecycle
  (start [this]
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
                 :asset-path "/static/editor"}})
    this)
  (stop [this]
    (println "[Figwheel] Stopping figwheel build")
    (fig/stop "dev")
    this))