(ns grumpy.core.jobs
  (:require
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [grumpy.core.log :as log]))


(defn try-async
  ([name f] (try-async name f {}))
  ([name f {:keys [after retries interval-ms]
            :or {after       identity
                 retries     5
                 interval-ms 1000}}]
    (future
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
          (.printStackTrace e))))))


(defn sh [& args]
  (apply log/log "sh:" (mapv pr-str args))
  (let [{:keys [exit out err] :as res} (apply shell/sh args)]
    (log/log "exit:" exit "out:" (pr-str out) "err:" (pr-str err))
    (if (= 0 exit)
      res
      (throw (ex-info (str "External process failed: " (str/join " " args) " returned " exit)
               (assoc res :args args))))))