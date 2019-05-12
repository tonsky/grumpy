(ns grumpy.db
  (:require
   [crux.api :as crux]
   [grumpy.core :as grumpy]
   [compact-uuids.core :as uuid]
   [com.stuartsierra.component :as component]))


(defrecord Crux [opts system]
  component/Lifecycle
  (start [this]
    (println "[db] Starting Crux with" opts)
    (assoc this :system (crux/start-standalone-system opts)))
  (stop [this]
    (println "[db] Stopping Crux")
    (.close system)
    (dissoc this :system)))


(def default-opts
  {:kv-backend    "crux.kv.rocksdb.RocksKv"
   :db-dir        "grumpy_data/crux_db"
   :event-log-dir "grumpy_data/crux_events"
   :backup-dir    "grumpy_data/crux_backup"})


(defn crux
  ([] (crux {}))
  ([opts]
   (let [opts' (merge-with #(or %2 %1) default-opts opts)]
     (map->Crux {:opts opts'}))))


(def post-id-high   (#'uuid/parse-long "000grvmpyp0st" 0))
(def pict-id-high   (#'uuid/parse-long "000grvmpyp1ct" 0))
(def repost-id-high (#'uuid/parse-long "0grvmpyrep0st" 0))


(defn put
  ([entity]
   [:crux.tx/put (:crux.db/id entity) (grumpy/filtermv some? entity)])
  ([entity valid-time]
   [:crux.tx/put (:crux.db/id entity) (grumpy/filtermv some? entity) valid-time]))


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
      (grumpy/update-some :post/picture entity)
      (grumpy/update-some :post/picture-original entity)
      (grumpy/update-some :post/reposts #(mapv entity %)))))


(defn post-by-idx [system idx]
  (get-post system (grumpy/make-uuid post-id-high idx)))


(defn post-by-url [system url]
  (let [db (crux/db system)]
    (when-some [crux-id (ffirst (crux/q db {:find ['e] :where '[[e :post/url url]] :args [{:url url}]}))]
      (get-post system crux-id))))