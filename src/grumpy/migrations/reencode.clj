(ns grumpy.migrations.reencode
  (:require
   [clojure.string :as str]
   [clojure.java.io :as io]
   [grumpy.core :as grumpy]
   [grumpy.authors :as authors]))


(defn -main [& args]
  (println "Converting videos...")
  (doseq [id (grumpy/post-ids)
          :let [post (grumpy/get-post id)
                pic  (:picture post)]
          :when (some? pic)
          :when (nil? (:picture-original post))
          :when (string? (:content-type pic))
          :when (str/starts-with? (:content-type pic) "video/")]
    (let [dir        (io/file (str "grumpy_data/posts/" id))
          [name ext] (str/split (:url pic) #"\.")
          _          (println "  converting" id "/" (:url pic))
          original   (io/file dir (str name ".orig." ext))
          _          (grumpy/sh "mv"
                       (str "grumpy_data/posts/" id "/" (:url pic))
                       (.getPath original))
          converted  (io/file dir (str name ".mp4"))
          _          (authors/convert-video! original converted)
          post'      (assoc post
                       :picture
                       {:url          (.getName converted)
                        :content-type "video/mp4"
                        :dimensions   (authors/video-dimensions converted) }
                       :picture-original
                       (assoc pic
                         :url        (.getName original)
                         :dimensions (authors/video-dimensions original)))]
      (spit (io/file dir "post.edn") (pr-str post'))))
  (println "Done converting videos")
  (shutdown-agents))