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
  (let [out (:out (jobs/sh "ffprobe" "-v" "error" "-select_streams" "v" "-show_entries" "stream=width,height,nb_frames:stream_side_data=rotation" "-of" "csv=p=0:s=x" (.getPath file)))]
    (if-some [[_ w h frames rotation] (re-find #"^(\d+|N/A)x(\d+|N/A)x(\d+|N/A)(?:x(-?\d+))?" out)]
      ;; rotated videos store a landscape buffer + rotation side data;
      ;; report display dimensions
      (let [rotation (some-> rotation Long/parseLong)
            swap?    (and rotation (#{90 270} (mod rotation 360)))]
        {:width  (parse-long (if swap? h w))
         :height (parse-long (if swap? w h))
         :frames (parse-long frames)})
      (throw (ex-info (str "Unexpected ffprobe output: " out) {:out out})))))


(defn dimensions [^File file]
  (let [stats (stats file)]
    [(:width stats) (:height stats)]))


(defn local-convert! [^File original ^File converted]
  ;; iw/ih in the filter are post-autorotation dimensions, so rotated (phone)
  ;; videos keep their display orientation. min() prevents upscaling
  (jobs/sh "ffmpeg"
    "-i"           (.getPath original)
    "-c:v"         "libx264"
    "-crf"         "18"
    "-movflags"    "+faststart"
    "-vf"          "scale=w='min(1100,iw)':h='min(1000,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2"
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
    (.getPath converted)))
