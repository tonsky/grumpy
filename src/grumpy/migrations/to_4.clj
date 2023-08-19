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
    [java.io File]
    [java.nio.file CopyOption Files Path StandardCopyOption]))

(defn convert-picture [picture year id suffix]
  (when picture
    (let [{:keys [url content-type dimensions]} picture
          ext            (files/extension url)
          new-url        (format "%d/%d_1%s.%s" year id suffix ext)
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
    :crosspost.tg.media/file-id        (get doc "file_id")
    :crosspost.tg.media/file-unique-id (get doc "file_unique_id")
    :crosspost.tg.media/file-size      (get doc "file_size")
    :crosspost.tg.media/width          (get doc "width")
    :crosspost.tg.media/height         (get doc "height")))

(defn convert-crosspost [crosspost]
  (coll/some-map
    :crosspost/type          (case (:type crosspost)
                               :telegram/photo :tg/media
                               :telegram/text  :tg/text)
    :crosspost.tg/channel    (:telegram/channel crosspost)
    :crosspost.tg/message-id (:telegram/message_id crosspost)
    :crosspost.tg/media      (map convert-tg-media (:telegram/photo crosspost))))

(defn convert-post [id post]
  (let [year (time/year (:created post))]
    (coll/some-map
      :post/id         id
      :post/old-id     (:id post)
      :post/body       (:body post)
      :post/author     (:author post)
      :post/created    (:created post)
      :post/updated    (:updated post)
      :post/media      (convert-picture (:picture post) year id "")
      :post/media-full (convert-picture (:picture-original post) year id "_full")            
      :post/crosspost  (map convert-crosspost (:reposts post)))))

(defn posts []
  (->>
    (for [^File file (file-seq (io/file "grumpy_data" "posts"))
          :when (= "post.edn" (.getName file))]
      (files/read-edn-string (slurp file)))
    (sort-by :created)
    (vec)))

(defn db
  ([posts]
   (db posts {}))
  ([posts opts]
   (-> (d/empty-db db/schema opts)
     (d/db-with
       (map convert-post (range 1 Long/MAX_VALUE) posts)))))

(defn migrate! []
  (let [posts (posts)
        db    (db posts)]
    (try
      (mount/start #'db/storage)
      (d/store db db/storage)
      (finally
        (mount/stop #'db/storage)))
    
    (doseq [year (range 2017 2024)]
      (.mkdirs (io/file "grumpy_data" (str year))))
  
    (doseq [d (d/datoms db :aevt :media/url)
            :let [media (d/entity db (:e d))
                  post  (coll/single 
                          (or
                            (:post/_media media)
                            (:post/_media-full media)))
                  from  (str (:post/old-id post) "/" (:media/old-url media))
                  to    (:media/url media)]]
      (Files/copy
        (Path/of "grumpy_data" (into-array String ["posts" from]))
        (Path/of "grumpy_data" (into-array String [to]))
        ^{:tag "[Ljava.nio.file.CopyOption;"} (into-array CopyOption [StandardCopyOption/COPY_ATTRIBUTES StandardCopyOption/REPLACE_EXISTING])))
    
    ; (doseq [file (reverse (file-seq (io/file "grumpy_data" "drafts")))]
    ;   (.delete ^File file))
    
    ; (doseq [file (reverse (file-seq (io/file "grumpy_data" "posts")))]
    ;   (.delete ^File file))
    
    ))

(comment
  (.delete (io/file "grumpy_data/db.sqlite"))
  (migrate!)
  (.exists (io/file "grumpy_data/posts/0OQVxoeuh_1.jpg")))
