(ns grumpy.migrations.to-3
  (:require
   [grumpy.core.coll :as coll]
   [grumpy.migrations.to-2 :as migrations.to-2]))


(defn update-2->3 [post]
  (let [orig  (select-keys (:picture-original post) [:telegram/message_id :telegram/photo])
        pic   (select-keys (:picture post)          [:telegram/message_id :telegram/photo])
        tg-id (:telegram/message_id post)]
    (cond-> post
      (some? tg-id)
      (-> (update :reposts coll/conjv {:type :telegram/text
                                         :telegram/channel "whining"
                                         :telegram/message_id tg-id})
        (dissoc :telegram/message_id))

      (not-empty orig)
      (-> (update :reposts coll/conjv (assoc orig :type :telegram/photo, :telegram/channel "whining"))
        (update :picture-original dissoc :telegram/message_id :telegram/photo))

      (not-empty pic)
      (-> (update :reposts coll/conjv (assoc pic :type :telegram/photo, :telegram/channel "whining"))
        (update :picture dissoc :telegram/message_id :telegram/photo)))))


(defn migrate! []
  (migrations.to-2/update-every-post! update-2->3))