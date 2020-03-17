(ns grumpy.core.drafts
  (:refer-clojure :exclude [load])
  (:require
   [clojure.java.io :as io]
   [grumpy.core.files :as files]
   [grumpy.core.jobs :as jobs])
  (:import
   [java.io File]))


(defn create-new [user]
  (let [post-id (str "@" user)]
    (jobs/linearize post-id
      (let [draft-dir (io/file "grumpy_data/drafts" post-id)
            draft     (io/file draft-dir "post.edn")
            edn       {:author user :body "" :id post-id}]
        (.mkdirs draft-dir)
        (spit draft (pr-str edn))
        edn))))


(defn create-edit [post-id]
  (jobs/linearize post-id
    (let [original-dir (io/file "grumpy_data/posts" post-id)
          original     (io/file original-dir "post.edn")
          draft-dir    (io/file "grumpy_data/drafts" post-id)
          draft        (io/file draft-dir "post.edn")]
      (files/copy-dir original-dir draft-dir)
      (files/read-edn-string (slurp draft)))))


(defn load [post-id]
  (jobs/linearize post-id
    (let [draft (io/file "grumpy_data/drafts" post-id "post.edn")]
      (when (.exists draft)
        (files/read-edn-string (slurp draft))))))


(defn update! [post-id update-fn]
  (jobs/linearize post-id
    (let [draft  (load post-id)
          draft' (update-fn draft)
          file   (io/file "grumpy_data/drafts" post-id "post.edn")]
      (spit file (pr-str draft'))
      draft')))


(defn delete! [post-id]
  (jobs/linearize post-id
    (files/delete-dir (str "grumpy_data/drafts/" post-id))))


(defn delete-media! [post-id]
  (jobs/linearize post-id
    (let [dir (io/file "grumpy_data/drafts/" post-id)]
      (doseq [^File file (next (file-seq dir))
              :when (not= "post.edn" (.getName file))]
        (.delete file)))
    (update! post-id #(dissoc % :picture :picture-original))))

