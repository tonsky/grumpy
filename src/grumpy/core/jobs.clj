(ns grumpy.core.jobs
  (:require
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [grumpy.core.coll :as coll]
   [grumpy.core.log :as log]))


(defonce **agents (atom {}))
(defonce ^:dynamic current-agent-id nil)


(defn try-async
  ([name f] (try-async name f {}))
  ([name f {:keys [after retries interval-ms]
            :or {after       identity
                 retries     5
                 interval-ms 1000}}]
    (future
      (binding [current-agent-id nil]
        (try
          (loop [i 0]
            (if (< i retries)
              (let [[success? res] (try
                                    [true (f)]
                                    (catch Exception e
                                      (log/log "[" name "] Try #" i" failed" (pr-str (ex-data e)))
                                      (.printStackTrace e)
                                      [false nil]))]
                (if success?
                  (after res)
                  (do
                    (Thread/sleep interval-ms)
                    (recur (inc i)))))
                (log/log "[" name "] Giving up after" retries "retries")))
          (catch Exception e
            (log/log "[" name "] Something went wrong" (pr-str (ex-data e)))
            (.printStackTrace e)))))))


(defn sh [& args]
  (apply log/log "sh:" (mapv pr-str args))
  (let [{:keys [exit out err] :as res} (apply shell/sh args)]
    (log/log "exit:" exit "out:" (pr-str out) "err:" (pr-str err))
    (if (= 0 exit)
      res
      (throw (ex-info (str "External process failed: " (str/join " " args) " returned " exit)
               (assoc res :args args))))))


(defn linearize-async [**agents agent-id f]
  (cond
    (nil? current-agent-id)
    (let [*a   (-> (swap! **agents coll/assoc-new agent-id (agent nil))
                 (get agent-id))
          *res (promise)]
      (send *a
        (fn [_]
          (try
            (binding [current-agent-id agent-id]
              (deliver *res [:success (f)]))
            nil
            (catch Throwable t
              (deliver *res [:throwable t])))))
      *res)

    (= current-agent-id agent-id)
    (deliver (promise) [:success (f)])

    (not= current-agent-id agent-id)
    (throw (IllegalStateException. (str "Trying to linearize " agent-id " while already in " current-agent-id)))))


(defn block [*promise timeout]
  (let [[status value] (deref *promise timeout [:timeout])]
    (case status
      :success   value
      :throwable (throw value)
      :timeout   (throw (java.util.concurrent.TimeoutException.)))))
 

 (defmacro linearize [agent-id & body]
  `(-> (jobs/linearize-async **agents ~agent-id (fn [] ~@body))
     (block 60000)))