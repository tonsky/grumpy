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

(def lock
  (Object.))

(defn position []
  (let [trace (->> (Thread/currentThread)
                (.getStackTrace)
                (seq))
        el    ^StackTraceElement (nth trace 4)]
    (str "[" (clojure.lang.Compiler/demunge (.getClassName el)) " " (.getFileName el) ":" (.getLineNumber el) "]")))

(defn p [form]
  `(let [t# (System/currentTimeMillis)
         res# ~form]
     (locking lock
       (println (str "#p" (position) " " '~form " => (" (- (System/currentTimeMillis) t#) " ms) " res#)))
     res#))

(set! *data-readers*
  (assoc *data-readers* 'c/uuid #'uuid/read))

(defn stop []
  (mount/stop))

(defn refresh []
  (let [max-addr @@(requiring-resolve 'datascript.storage/*max-addr)
        _   (set! *warn-on-reflection* true)
        res (namespace/refresh)]    
    (when (not= :ok res)
      (.printStackTrace ^Throwable res)
      (throw res))
    
    (when-some [*max-addr (requiring-resolve 'datascript.storage/*max-addr)]
      (vreset! @*max-addr max-addr))
    
    (when-some [*opts @(requiring-resolve 'grumpy.server/*opts)]
      (swap! *opts assoc :host "0.0.0.0"))
    
    :ok))

(defn start []
  (mount/start-without
    (resolve 'grumpy.figwheel/figwheel)))

(defn reload []
  (stop)
  (refresh)
  (start)
  :ready)

(defn cljs-repl []
  (figwheel.main.api/cljs-repl "dev"))

(migrations/migrate!)

(reload)

(println "[user] Socket REPL server on port 5555")
(println "[user] Run (reload) for full system reload")
(println "[user] Run (cljs-repl) for upgrading REPL to CLJS")
