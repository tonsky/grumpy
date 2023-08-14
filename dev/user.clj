(ns user
  (:require
    [clojure.tools.namespace.repl :as namespace]
    [compact-uuids.core :as uuid]
    [figwheel.main.api]
    [grumpy.figwheel :as figwheel]
    [grumpy.migrations :as migrations]
    [mount.core :as mount]))

(namespace/disable-reload!)

(namespace/set-refresh-dirs "src" "dev")

(defmethod print-method java.util.UUID [uuid ^java.io.Writer w]
  (.write w (str "#c/uuid \"" (uuid/str uuid) "\"")))

(set! *data-readers*
  (assoc *data-readers* 'c/uuid #'uuid/read))

(defn stop []
  (mount/stop))

(defn refresh []
  (let [res (namespace/refresh)]
    (when (not= res :ok)
      (throw res))
    :ok))

(defn start []
  (mount/start-without
    (resolve 'grumpy.figwheel/figwheel)))

(defn reload []
  (stop)
  (refresh)
  (start))

(defn cljs-repl []
  (figwheel.main.api/cljs-repl "dev"))

(migrations/migrate!)

(reload)

(println "[user] Socket REPL server on port 5555")
(println "[user] Run (reload) for full system reload")
(println "[user] Run (cljs-repl) for upgrading REPL to CLJS")
