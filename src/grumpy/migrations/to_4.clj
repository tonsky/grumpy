(ns grumpy.migrations.to-4
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [datascript.core :as d]
    [grumpy.core.coll :as coll]
    [grumpy.core.files :as files]
    [grumpy.core.time :as time]
    [grumpy.db :as db]
    [mount.core :as mount])
  (:import
    [java.io File]))

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

(defn posts []
  (->>
    (for [^File file (file-seq (io/file "grumpy_data" "posts"))
          :when (= "post.edn" (.getName file))]
      (files/read-edn-string (slurp file)))
    (sort-by :created)
    (vec)))

(defn db [posts]
  (d/db-with
    (d/empty-db db/schema)
    (map
      (fn [i post]
        (let [year (time/year (:created post))]
          (coll/some-map
            :post/id         i
            :post/old-id     (:id post)
            :post/body       (:body post)
            :post/author     (:author post)
            :post/created    (:created post)
            :post/updated    (:updated post)
            :post/media      (convert-picture (:picture post) year i "")
            :post/media-full (convert-picture (:picture-original post) year i "_full")            
            :post/crosspost  (map convert-crosspost (:reposts post)))))                
      (range) 
      posts)))

(defn migrate! []
  (let [posts (posts)
        db    (db posts)]
    (try
      (mount/start #'db/storage)
      (d/store db db/storage)
      (finally
        (mount/stop #'db/storage)))))
