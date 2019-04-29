(ns grumpy.db
  (:require
    [clojure.edn :as edn]
    [grumpy.core :as grumpy]))


(def expected-db-version 3)


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


(defn update-1->2 [post]
  (let [[pic] (:pictures post)]
    (cond-> post
      true (dissoc :pictures)
      (some? pic) (assoc :picture { :url pic }))))


(defn update-2->3 [post]
  (let [orig  (select-keys (:picture-original post) [:telegram/message_id :telegram/photo])
        pic   (select-keys (:picture post)          [:telegram/message_id :telegram/photo])
        tg-id (:telegram/message_id post)]
    (cond-> post
      (some? tg-id)
      (-> (update :reposts grumpy/conjv {:type :telegram/text
                                         :telegram/channel "whining"
                                         :telegram/message_id tg-id})
        (dissoc :telegram/message_id))

      (not-empty orig)
      (-> (update :reposts grumpy/conjv (assoc orig :type :telegram/photo, :telegram/channel "whining"))
        (update :picture-original dissoc :telegram/message_id :telegram/photo))

      (not-empty pic)
      (-> (update :reposts grumpy/conjv (assoc pic :type :telegram/photo, :telegram/channel "whining"))
        (update :picture dissoc :telegram/message_id :telegram/photo)))))


(when (not= db-version expected-db-version)
  (spit "grumpy_data/DB_VERSION" (str expected-db-version))
  (alter-var-root #'db-version (constantly expected-db-version)))