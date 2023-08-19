(ns grumpy.db
  (:require
    [datascript.core :as d]
    [datascript.storage.sql.core :as storage-sql]
    [grumpy.core.transit :as transit]
    [mount.core :as mount])
  (:import
    [java.sql DriverManager]))

(def schema
  {:post/id              {#_:db.type/long
                          :db/unique :db.unique/identity}
   :post/old-id          {#_:db.type/string
                          :db/unique :db.unique/identity}
   :post/body            {#_:db.type/string}
   :post/author          {#_:db.type/string}
   :post/created         {#_:db.type/instant
                          :db/index true}
   :post/updated         {#_:db.type/instant}
   :post/deleted?        {#_:db.type/boolean}
   :post/media           {:db/valueType   :db.type/ref
                          :db/isComponent true}
   :post/media-full      {:db/valueType   :db.type/ref
                          :db/isComponent true}
   :media/url            {#_:db.type/string
                          :db/unique :db.unique/identity}
   :media/old-url        {#_:db.type/string
                          :db/unique :db.unique/identity}
   :media/content-type   {#_:db.type/string}
   :media/width          {#_:db.type/long}
   :media/height         {#_:db.type/long}
   :media/version        {#_:db.type/long}
   :post/crosspost       {:db/valueType   :db.type/ref
                          :db/cardinality :db.cardinality/many
                          :db/isComponent true}
   :crosspost/type          {#_:db.type/keyword} ;; :tg/media :tg/text
   :crosspost.tg/channel    {#_:db.type/string}
   :crosspost.tg/message-id {#_:db.type/string}
   :crosspost.tg/media      {:db/valueType   :db.type/ref
                             :db/cardinality :db.cardinality/many
                             :db/isComponent true}
   :crosspost.tg.media/file-id        {#_:db.type/string}
   :crosspost.tg.media/file-unique-id {#_:db.type/string}
   :crosspost.tg.media/file-size      {#_:db.type/string}
   :crosspost.tg.media/width          {#_:db.type/string}
   :crosspost.tg.media/height         {#_:db.type/string}
   :crosspost.tg.media/mime-type      {#_:db.type/string}
   :crosspost.tg.media/duration       {#_:db.type/long}
   :crosspost.tg.media/thumbnail      {:db/valueType :db.type/ref
                                       :db/isComponent true}})

(defn make-storage [path]
  (let [conn (DriverManager/getConnection (str "jdbc:sqlite:" path))]
    (storage-sql/make conn
      {:dbtype       :sqlite
       :freeze-bytes #(transit/write-bytes % :msgpack)
       :thaw-bytes   #(transit/read-bytes % :msgpack)})))

(mount/defstate storage
  :start
  (make-storage "grumpy_data/db.sqlite")
  :stop
  (storage-sql/close storage))

(mount/defstate conn
  :start
  (or
    (d/restore-conn storage)
    (d/create-conn schema {:storage storage})))

(defn db []
  @conn)

(defn insert! [conn tempid tx]
  (let [report (d/transact! conn tx)]
    (d/pull (:db-after report) '[*] ((:tempids report) tempid))))

(defmacro transform! [[db conn] & body]
  `(d/transact! ~conn
     [[:db.fn/call (fn [~db] ~@body)]]))

(comment
  (count (datascript.storage/-list-addresses storage))
  
  (max-id (db) :draft/id)
  (count (db))
  (d/reset-schema! conn schema)
  (binding [datascript.util/*debug* true]
    (d/collect-garbage storage))
  
  (count (d/restore storage))
  
  (mount/start #'storage #'conn)
  
  (->> (d/datoms @conn :eavt)
    (drop 100)
    (take 10)
    (mapv (juxt :e :a :v)))
  
  (d/pull (db) '[:post/id :post/author :post/body {:post/media [*]}] [:post/id 1600])
  
  (->> (d/datoms (db) :aevt :post/deleted?)
    (map #(:post/id (d/entity (db) (:e %)))))

  )
