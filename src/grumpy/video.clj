(ns grumpy.video
  (:refer-clojure :exclude [parse-long])
  (:require
   [grumpy.core.jobs :as jobs])
  (:import
   [java.io File]))


(defn parse-long [s]
  (if (= "N/A" s)
    nil
    (Long/parseLong s)))


(defn stats [^File file]
  (let [out (:out (jobs/sh "ffprobe" "-v" "error" "-select_streams" "v" "-show_entries" "stream=width,height,nb_frames" "-of" "csv=p=0:s=x" (.getPath file)))]
    (if-some [[_ w h frames] (re-find #"^(\d+|N/A)x(\d+|N/A)x(\d+|N/A)" out)]
      {:width  (parse-long w)
       :height (parse-long h)
       :frames (parse-long frames)}
      (throw (ex-info (str "Unexpected ffprobe output: " out) {:out out})))))


(defn dimensions [^File file]
  (let [stats (stats file)]
    [(:width stats) (:height stats)]))


(defn local-convert!
  ([original converted]
   (local-convert! original converted (dimensions original)))
  ([^File original ^File converted [w h]]
   (let [aspect  (/ w h)
         [w1 h1] (if (> w 1100) [1100 (/ 1100 aspect)] [w h])
         [w2 h2] (if (> h1 1000) [(* aspect 1000) 1000] [w1 h1])
         round   (fn [x] (-> x (/ 2) long (* 2)))
         [w3 h3] [(round w2) (round h2)]]
     (jobs/sh "ffmpeg"
       "-i"           (.getPath original)
       "-c:v"         "libx264"
       "-crf"         "18"
       "-movflags"    "+faststart"
       "-vf"          (str "scale=" w3 ":" h3)
       "-r"           "30" ; fps
       "-profile:v"   "main"
       "-level:v"     "3.1"
       "-pix_fmt"     "yuv420p"
       "-y"           ; override existing
       "-loglevel"    "warning"
       "-hide_banner"
       "-map"         "0:v:0"
       "-threads"     "3"
       "-an"
       (.getPath converted)))))
