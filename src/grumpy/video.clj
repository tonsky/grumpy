(ns grumpy.video
  (:require
   [clj-http.client :as http]
   [clojure.string :as str]
   [clojure.java.io :as io]
   [grumpy.core.coll :as coll]
   [grumpy.core.config :as config]
   [grumpy.core.jobs :as jobs]
   [grumpy.core.log :as log])
  (:import
   [java.io File InputStream]))


(def CIRCLECI_URL "https://circleci.com/api/v1.1/project/gh/tonsky/grumpy_video")
(def MAX_JOB_TIME_MS 60000) ;; 1 min


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


(defn local-convert! [^File original ^File converted]
  (let [[w h]   (dimensions original)
        aspect  (/ w h)
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
      "-y"           ; override existing
      "-loglevel"    "warning"
      "-hide_banner"
      "-an"
      (.getPath converted))))


(defn request
  ([method url] (request method url {}))
  ([method url opts]
   (log/log method url)
   (:body
    ((case method :get http/get :post http/post)
     url
     (merge
       {:cookie-policy :none
        :as            :json-string-keys}
       opts)))))


(defn request-circleci
  ([method url] (request-circleci method url {}))
  ([method url opts]
   (request method (str CIRCLECI_URL url) 
     (merge
       {:basic-auth [(config/get ::circleci-token) ""]
        :accept     :json}
       opts))))


#_(defn estimate-progress [build-resp]
  (when-some [step (some-> (get build-resp "steps")
                     (->> (coll/seek #(= "$REMOTE_COMMAND" (get % "name"))))
                     (get "actions")
                     (->> (coll/seek #(= "$REMOTE_COMMAND" (get % "name")))))]
    (when-some [output (request-circleci :get "/" (get build-resp "build_num") "/output/" (get step "step") "/" (get step "index"))]
      (when-some [last-message (-> (last output) (get "message"))]
        (when-some [[_ frame] (last (re-seq #"frame=\s*(\d+)" last-message))]
          (Long/parseLong frame))))))


(defn convert! [^File original url ^File converted]
  (let [{w :width h :height frames :frames} (stats original)
        aspect  (/ w h)
        [w1 h1] (if (> w 1000) [1000 (/ 1000 aspect)] [w h])
        [w2 h2] (if (> h1 1100) [(* aspect 1100) 1100] [w1 h1])
        round   (fn [x] (-> x (/ 2) long (* 2)))
        [w3 h3] [(round w2) (round h2)]
        command (str "ffmpeg -i " url " -c:v libx264 -crf 18 -movflags +faststart -vf scale=" w3 ":" h3 " -r 30 -profile:v main -level:v 3.1 -y -hide_banner -f mp4 -an /tmp/output.mp4")
        submit-resp (request-circleci :post ""
                      {:content-type :json
                       :form-params  {:build_parameters {:REMOTE_COMMAND command}}})
        build-num   (get submit-resp "build_num")
        started     (System/currentTimeMillis)
        *result     (atom {:status    (get submit-resp "status")
                           :outcome   nil
                           :started   started
                           :updated   started
                           :original  original
                           :build-num build-num})
        check-fn    (fn []
                      (Thread/sleep 1000)
                      (let [build-resp (request-circleci :get (str "/" build-num))
                            outcome    (get build-resp "outcome")
                            now        (System/currentTimeMillis)
                            updated    {:status  (get build-resp "status")
                                        :outcome outcome
                                        :updated now}]
                        (cond
                          ;; still in progress
                          (nil? outcome)
                          (do
                            (swap! *result merge updated)
                            ;; taking too long
                            (when (> (- now started) MAX_JOB_TIME_MS)
                              (request-circleci :post (str "/" build-num "/cancel")))
                            (recur))

                          ;; finished
                          (= "success" outcome)
                          (let [artifacts-resp (request-circleci :get (str "/" build-num "/artifacts"))
                                artifact-url   (-> artifacts-resp (first) (get "url"))]
                            (with-open [^InputStream artifact-input (request :get artifact-url {:as :stream})]
                              (io/copy artifact-input converted))
                            (swap! *result merge updated
                              {:finished  (System/currentTimeMillis)
                               :converted converted}))

                          ;; failed or other reasons
                          :else
                          (swap! *result merge updated {:finished now}))))]
    (doto (Thread. ^Runnable check-fn)
      (.setName (str "Convert " url))
      (.start))
    *result))

(comment
  (in-ns 'grumpy.video)

  (add-watch
    (convert!
      (io/file "grumpy_data/drafts/@nikitonsky/Lt59l67.orig.mp4")
      "https://grumpy.website/draft/@nikitonsky/Lt59l67.orig.mp4"
      (io/file "grumpy_data/drafts/@nikitonsky/Lt59l67.mp4"))
    :prn
    (fn [_ _ _ new] (prn new)))

  (def build-resp (request-circleci :get "/13"))
  (estimate-progress build-resp)

  (http/get "https://circleci.com/api/v1.1/me"
    {:basic-auth [(config/get ::circleci-token) ""]
     :accept :json
     :as :json-string-keys})
)