(ns user
  (:require
   [clojure.tools.namespace.repl :as namespace]
   [com.stuartsierra.component :as component]
   [figwheel.main.api]
   [grumpy.figwheel :as figwheel]))


(namespace/disable-reload!)
(namespace/set-refresh-dirs "src" "dev")


(defonce *system (atom nil))
(defonce *figwheel (atom nil))


(defn stop []
  (some-> @*system (component/stop))
  (reset! *system nil))


(defn refresh []
  (let [res (namespace/refresh)]
    (when (not= res :ok)
      (throw res))
    :ok))


(defn start 
  ([] (start {}))
  ([opts]
    (let [opts' (merge-with merge {:server {:host "0.0.0.0"}} opts)]
      (when-some [f (resolve 'grumpy.system/system)]
        (when-some [system (f opts')]
          (when-some [system' (component/start system)]
            (reset! *system system')))))))


(defn reset []
  (stop)
  (refresh)
  (start))


(defn cljs-repl []
  (figwheel.main.api/cljs-repl "dev"))


(reset! *figwheel
  (component/start (figwheel/map->Figwheel {:opts {:build "dev"}})))


(reset)


(println "[user] Run (reset) for full system reload")
(println "[user] Run (cljs-repl) for upgrading REPL to CLJS")
(println "[user] Open http://localhost:8080/")