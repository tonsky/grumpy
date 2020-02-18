(ns user
  (:require
   [figwheel.main.api]
   [compact-uuids.core :as uuid]
   [grumpy.figwheel :as figwheel]
   [grumpy.migrations :as migrations]
   [com.stuartsierra.component :as component]
   [clojure.tools.namespace.repl :as namespace]))


(namespace/disable-reload!)
(namespace/set-refresh-dirs "src" "dev")


(defmethod print-method java.util.UUID [uuid ^java.io.Writer w]
  (.write w (str "#c/uuid \"" (uuid/str uuid) "\"")))

(set! *data-readers* (assoc *data-readers* 'c/uuid #'uuid/read))


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
      (when-some [f (resolve 'grumpy.main/system)]
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
  (component/start (figwheel/->Figwheel)))


(migrations/migrate!)
(reset)


(println "[user] Run (reset) for full system reload")
(println "[user] Run (cljs-repl) for upgrading REPL to CLJS")
(println "[user] Open http://localhost:8080/")