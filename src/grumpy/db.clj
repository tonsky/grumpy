(ns grumpy.db
  (:require
    [clojure.edn :as edn]
    [grumpy.core :as grumpy]))


(def expected-db-version 2)


(def db-version (Long/parseLong (grumpy/from-config "DB_VERSION" "1")))


(defn migrate! [version f]
  (when (< db-version version)
    (println "Migrating DB to version" version)
    (doseq [post-id (grumpy/post-ids)
            :let [file (str "grumpy_data/posts/" post-id "/post.edn")
                  post (edn/read-string (slurp file))]]
      (try
        (spit file (pr-str (f post)))
        (catch Exception e
          (println "Can’t convert" file)
          (.printStackTrace e))))))


(migrate! 2
  (fn [post]
    (let [[pic] (:pictures post)]
      (cond-> post
        true (dissoc :pictures)
        (some? pic) (assoc :picture { :url pic })))))


(when (not= db-version expected-db-version)
  (spit "grumpy_data/DB_VERSION" (str expected-db-version))
  (alter-var-root #'db-version (constantly expected-db-version)))
