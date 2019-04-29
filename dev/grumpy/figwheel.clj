(ns grumpy.figwheel
  (:require
   [com.stuartsierra.component :as component]
   [cljs.stacktrace]
   [figwheel.main.api :as fig]))

(defrecord Figwheel [opts]
  component/Lifecycle
  (start [this]
    (println "[Figwheel] Starting figwheel build" (:build opts))
    (fig/start {:mode               :serve
                :rebel-readline     false
                :cljs-devtools      false
                :helpful-classpaths false
                :open-url           false}
      (:build opts))
    this)
  (stop [this]
    (println "[Figwheel] Stopping figwheel build" (:build opts))
    (fig/stop (:build opts))
    this))