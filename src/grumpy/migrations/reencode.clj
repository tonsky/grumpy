(ns grumpy.migrations.reencode
  (:require
   [clojure.string :as str]
   [clojure.java.io :as io]
   [grumpy.core.log :as log]
   [grumpy.core.jobs :as jobs]
   [grumpy.core.posts :as posts]
   [grumpy.video :as video]))


(defn -main [& args]
  (log/log "Converting videos...")
  (doseq [id (posts/post-ids)
          :let [post (posts/load id)
                pic  (:picture post)]
          :when (some? pic)
          :when (nil? (:picture-original post))
          :when (string? (:content-type pic))
          :when (str/starts-with? (:content-type pic) "video/")]
    (let [dir        (io/file (str "grumpy_data/posts/" id))
          [name ext] (str/split (:url pic) #"\.")
          _          (log/log "  converting" id "/" (:url pic))
          original   (io/file dir (str name ".orig." ext))
          _          (jobs/sh "mv"
                       (str "grumpy_data/posts/" id "/" (:url pic))
                       (.getPath original))
          converted  (io/file dir (str name ".mp4"))
          _          (video/local-convert! original converted)
          post'      (assoc post
                       :picture
                       {:url          (.getName converted)
                        :content-type "video/mp4"
                        :dimensions   (video/dimensions converted) }
                       :picture-original
                       (assoc pic
                         :url        (.getName original)
                         :dimensions (video/dimensions original)))]
      (spit (io/file dir "post.edn") (pr-str post'))))
  (log/log "Done converting videos")
  (shutdown-agents))