(ns grumpy.db
  (:require
   [com.stuartsierra.component :as component]
   [compact-uuids.core :as uuid]
   [crux.api :as crux]
   [grumpy.core.coll :as coll]
   [grumpy.core.log :as log])
  (:import
   [java.util UUID Random]))


(defrecord Crux [opts system]
  component/Lifecycle
  (start [this]
    (log/log "[db] Starting Crux with" opts)
    (assoc this :system (crux/start-node opts)))
  (stop [this]
    (log/log "[db] Stopping Crux")
    (.close system)
    (dissoc this :system)))


(def default-opts
  {:crux.node/topology 'crux.standalone/topology
   :crux.node/kv-store 'crux.kv.rocksdb/kv
   :crux.kv/db-dir     "grumpy_data/crux_db"
   :crux.standalone/event-log-dir "grumpy_data/crux_events"})


(defn crux
  ([] (crux {}))
  ([opts]
   (let [opts' (merge-with #(or %2 %1) default-opts opts)]
     (map->Crux {:opts opts'}))))


(defn make-uuid
  ([hi]
   (UUID. hi (.nextLong (Random.))))
  ([hi low]
   (UUID. hi low)))


(def post-id-high   (#'uuid/parse-long "000grvmpyp0st" 0))
(def pict-id-high   (#'uuid/parse-long "000grvmpyp1ct" 0))
(def repost-id-high (#'uuid/parse-long "0grvmpyrep0st" 0))


(defn put
  ([entity]
   [:crux.tx/put (:crux.db/id entity) (coll/filtermv some? entity)])
  ([entity valid-time]
   [:crux.tx/put (:crux.db/id entity) (coll/filtermv some? entity) valid-time]))


(defn upsert [system attr document]
  (let [value (get document attr)
        id    (-> (crux/q (crux/db system)
                    {:find '[id] :where [['id attr 'value]] :args [{:value value}]})
                (ffirst)
                (or (java.util.UUID/randomUUID)))]
    (crux/submit-tx system [[:crux.tx/put id (assoc document :crux.db/id id)]])
    id))


(defn get-post [system id]
  (let [db      (crux/db system)
        entity  #(some->> % (crux/entity db))
        history (crux/history system id)]
    (-> (crux/entity db id)
      (assoc
        :post/created (:crux.db/valid-time (last history))
        :post/updated (:crux.db/valid-time (first history)))
      (coll/update-some :post/picture entity)
      (coll/update-some :post/picture-original entity)
      (coll/update-some :post/reposts #(mapv entity %)))))


(defn post-by-idx [system idx]
  (get-post system (make-uuid post-id-high idx)))


(defn post-by-url [system url]
  (let [db (crux/db system)]
    (when-some [crux-id (ffirst (crux/q db {:find ['e] :where '[[e :post/url url]] :args [{:url url}]}))]
      (get-post system crux-id))))