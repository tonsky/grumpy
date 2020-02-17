(ns grumpy.migrations.to-2
  (:require
   [clojure.edn :as edn]
   [grumpy.core.files :as files]
   [grumpy.core.log :as log]
   [grumpy.core.posts :as posts]))


(defn update-1->2 [post]
  (let [[pic] (:pictures post)]
    (cond-> post
      true (dissoc :pictures)
      (some? pic) (assoc :picture { :url pic }))))


(defn update-every-post! [f]
  (doseq [post-id (posts/post-ids)
          :let [file (str "grumpy_data/posts/" post-id "/post.edn")
                post (files/read-edn-string (slurp file))]]
    (try
      (spit file (pr-str (f post)))
      (catch Exception e
        (log/log "Can’t convert" file)
        (.printStackTrace e)))))


(defn migrate! []
  (update-every-post! update-1->2))