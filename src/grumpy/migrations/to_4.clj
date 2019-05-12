(ns grumpy.migrations.to-4
  (:require
   [grumpy.db :as db]
   [crux.api :as crux]
   [clojure.set :as set]
   [grumpy.core :as grumpy]
   [com.stuartsierra.component :as component]))


(defn convert-repost [valid-time repost id]
  [(db/put
     (-> repost
       (set/rename-keys {:type :repost/type})
       (assoc :crux.db/id id))
     valid-time)])


(defn convert-post [idx post]
  (let [{:keys [body author updated created id picture picture-original reposts]} post
        picture-id (when (some? picture) (grumpy/make-uuid db/pict-id-high))
        orig-id    (when (some? picture-original) (grumpy/make-uuid db/pict-id-high))
        repost-ids (repeatedly (count reposts) #(grumpy/make-uuid db/repost-id-high))
        post       {:crux.db/id   (grumpy/make-uuid db/post-id-high idx)
                    :post/url     id
                    :post/author  author
                    :post/body    body
                    :post/picture picture-id
                    :post/picture-original orig-id
                    :post/reposts (not-empty (vec repost-ids))}]
    (filterv some?
      (concat
        [(when-some [{:keys [url content-type dimensions]} picture]
           (db/put
             {:crux.db/id           picture-id
              :picture/url          url
              :picture/content-type content-type
              :picture/width        (first dimensions)
              :picture/height       (second dimensions)}
             created))
         (when-some [{:keys [url content-type dimensions]} picture-original]
           (db/put
             {:crux.db/id           orig-id
              :picture/url          url
              :picture/content-type content-type
              :picture/width        (first dimensions)
              :picture/height       (second dimensions)}
             created))
         (when (not= created updated)
           (db/put post created))
         (db/put post updated)]
        (mapcat #(convert-repost created %1 %2) reposts repost-ids)))))


(defn migrate! []
  (grumpy/delete-dir "grumpy_data/crux_db")
  (grumpy/delete-dir "grumpy_data/crux_events")
  (grumpy/delete-dir "grumpy_data/crux_backup")
  (let [{system :system :as crux} (-> (db/crux) (component/start))]
    (try
      (doseq [[idx id] (grumpy/zip
                         (range 1 Integer/MAX_VALUE)
                         (sort (grumpy/post-ids)))]
        (println "Converting" id "->" idx)
        (crux/submit-tx system (convert-post idx (grumpy/get-post id))))
      (finally
        (component/stop crux)))))