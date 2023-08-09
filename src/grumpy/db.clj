(ns grumpy.db
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [datascript.core :as d]
    [grumpy.core.coll :as coll]
    [grumpy.core.time :as time])
  (:import
    [java.io File]))

(def posts
  (->>
    (for [^File file (file-seq (io/file "grumpy_data" "posts"))
          :when (= "post.edn" (.getName file))]
      (edn/read-string (slurp file)))
    (sort-by :created)
    (vec)))

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

(defn convert-picture [picture year i suffix]
  (when picture
    (let [{:keys [url content-type dimensions]} picture
          ext            (last (str/split url #"\."))
          new-url        (format "%d/%d_1%s.%s" year i suffix ext)
          [width height] dimensions]
      (coll/some-map
        :media/url          new-url
        :media/old-url      url
        :media/version      1
        :media/content-type content-type
        :media/width        width
        :media/height       height))))

(defn convert-tg-media [doc]
  (coll/some-map
    :crosspost.tg.media/file-id        (:file_id doc)
    :crosspost.tg.media/file-unique-id (:file_unique_id doc)
    :crosspost.tg.media/file-size      (:file_size doc)
    :crosspost.tg.media/width          (:width doc)
    :crosspost.tg.media/height         (:height doc)))

(defn convert-crosspost [crosspost]
  (coll/some-map
    :crosspost/type          (case (:type crosspost)
                               :telegram/photo :tg/media
                               :telegram/text  :tg/text)
    :crosspost.tg/channel    (:telegram/channel crosspost)
    :crosspost.tg/message-id (:telegram/message_id crosspost)
    :crosspost.tg/media      (map convert-tg-media (:telegram/photo crosspost))))

(def db
  (d/db-with
    (d/empty-db schema)
    (map
      (fn [i post]
        (let [year (time/year (.toInstant (:created post)))]
          (coll/some-map
            :post/id      i
            :post/old-id  (:id post)
            :post/body    (:body post)
            :post/author  (:author post)
            :post/created (:created post)
            :post/updated (:updated post)
            
            :post/media      (convert-picture (:picture post) year i "")
            :post/media-full (convert-picture (:picture-original post) year i "_full")            
            :post/crosspost  (map convert-crosspost (:reposts post)))))                
      (range) 
      posts)))

(comment
  (d/q
    '[:find (pull ?e [*])
      :where
      [?ce :crosspost.tg/message-id 2646]
      [?e :post/crosspost ?ce]]
    db)
  
  (map (juxt :e :a :v)
    (drop 30010 (d/datoms db :eavt)))
  (count (d/datoms db :eavt))

  (nth posts 1000)
  (nth posts 1359)

  (def storage
    (d/file-storage "target/db"))

  (d/store db storage)

  (->> (d/datoms (d/restore storage) :eavt)
    (drop 10010)
    (take 100)
    (map (juxt :e :a :v))))