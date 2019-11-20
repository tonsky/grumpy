(ns grumpy.authors
  (:require
    [rum.core :as rum]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [clojure.java.io :as io]
    [ring.util.response :as response]
    [clojure.stacktrace :as stacktrace]
    [io.pedestal.http.ring-middlewares :as middlewares]
    
    [grumpy.auth :as auth]
    [grumpy.core :as grumpy]
    [grumpy.editor :as editor]
    [grumpy.macros :refer [cond+]]
    [grumpy.routes :as routes]
    [grumpy.transit :as transit]
    [grumpy.telegram :as telegram]))


(defn next-post-id [^java.util.Date inst]
  (str
    (grumpy/encode (quot (.getTime inst) 1000) 6)
    (grumpy/encode (rand-int (* 64 64 64)) 3)))


(defn copy-dir [from to]
  (.mkdirs to)
  (doseq [name (grumpy/list-files from)
          :let [file (io/file from name)]]
    (io/copy file (io/file to name))))


(defn get-draft [post-id]
  (let [draft    (io/file (str "grumpy_data/drafts/" post-id "/post.edn"))
        original (io/file (str "grumpy_data/posts/" post-id "/post.edn"))]
    (cond
      (.exists draft)
        (edn/read-string (slurp draft))
      (.exists original)
        (do
          (copy-dir (io/file (str "grumpy_data/posts/" post-id)) (io/file (str "grumpy_data/drafts/" post-id)))
          (edn/read-string (slurp draft)))
      :else
        (do
          (.mkdirs (io/file (str "grumpy_data/drafts/" post-id "/")))
          nil))))


(defn image-dimensions [file]
  (let [out (:out (grumpy/sh "convert" (.getPath file) "-ping" "-format" "[%w,%h]" "info:"))
        [w h] (edn/read-string out)]
    [w h]))


(defn convert-image! [from to opts]
  (apply grumpy/sh "convert"
    (.getPath from)
    (concat
      (flatten
        (for [[k v] opts
              :when (some? v)]
          (if (true? v)
            (str "-" (name k))
            [(str "-" (name k)) (str v)])))
      [(.getPath to)])))


(defn video-dimensions [file]
  (let [out (:out (grumpy/sh "ffprobe" "-v" "error" "-show_entries" "stream=width,height" "-of" "csv=p=0:s=x" (.getPath file)))
        [_ w h] (re-matches #"(\d+)x(\d+)" (str/trim out))]
    [(Long/parseLong w) (Long/parseLong h)]))


(defn convert-video! [original converted]
  (let [[w h]   (video-dimensions original)
        aspect  (/ w h)
        [w1 h1] (if (> w 1000) [1000 (/ 1000 aspect)] [w h])
        [w2 h2] (if (> h1 1100) [(* aspect 1100) 1100] [w1 h1])
        round   (fn [x] (-> x (/ 2) long (* 2)))
        [w3 h3] [(round w2) (round h2)]]
    (grumpy/sh "ffmpeg"
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


(defn save-picture! [post-id content-type input-stream]
  (let [draft (get-draft post-id)
        dir   (io/file (str "grumpy_data/drafts/" post-id))]
    (doseq [key   [:picture :picture-original]
            :let  [picture (get draft key)]
            :when (some? picture)
            :let  [file (io/file dir (:url picture))]
            :when (.exists file)]
      (io/delete-file file))
    (let [draft' (if (and (some? input-stream)
                          (some? content-type)
                          (pos? (.available input-stream)))
                   (let [[_ ext]  (str/split content-type #"/")
                         prefix   (grumpy/encode (System/currentTimeMillis) 7)
                         original (io/file dir (str prefix ".orig." ext))]
                     (io/copy input-stream original)
                     (cond+
                       ;; video
                       (str/starts-with? content-type "video/")
                       (assoc draft
                         :picture
                         { :url          (.getName original)
                           :content-type content-type
                           :dimensions   (video-dimensions original) })
                       #_(let [converted (io/file dir (str prefix ".mp4"))]
                         (convert-video! original converted)
                         (assoc draft
                           :picture
                           { :url          (.getName converted)
                             :content-type "video/mp4"
                             :dimensions   (video-dimensions converted) }
                           :picture-original
                           { :url          (.getName original)
                             :content-type content-type
                             :dimensions   (video-dimensions original)}))

                       (not (str/starts-with? content-type "image/"))
                       (throw (ex-info (str "Unknown content-type: " content-type) {:content-type content-type}))

                       :let [[w h] (image-dimensions original)]

                       ;; gif
                       (= "image/gif" content-type)
                       (assoc draft
                         :picture { :url (.getName original)
                                    :content-type content-type
                                    :dimensions [w h] })

                      :let [resize? (or (> w 1100) (> h 1000))]

                       ;; small jpeg
                       (and (= "image/jpeg" content-type) (not resize?))
                       (assoc draft
                         :picture { :url (.getName original)
                                    :content-type content-type
                                    :dimensions [w h] })

                       :else
                       (let [converted (io/file dir (str prefix ".fit.jpeg"))]
                         (convert-image! original converted
                           {:quality 85
                            :flatten true
                            :resize  (when resize? "1100x1000") })
                         (assoc draft
                           :picture
                           { :url          (.getName converted)
                             :content-type "image/jpeg"
                             :dimensions   (image-dimensions converted) }
                           :picture-original
                           { :url          (.getName original)
                             :content-type content-type
                             :dimensions   [w h] }))))
                       
                   (dissoc draft :picture :picture-original))]
      (spit (io/file dir "post.edn") draft')
      draft')))


(defn save-post! [post-id updates]
  (let [draft  (get-draft post-id)
        draft' (merge draft updates)
        dir    (str "grumpy_data/drafts/" post-id)
        file   (io/file dir "post.edn")]
    (spit (io/file file) (pr-str draft'))
    draft'))


(defn update-post! [post-id f]
  (let [post  (grumpy/get-post post-id)
        post' (f post)]
    (spit (io/file (str "grumpy_data/posts/" post-id) "post.edn") (pr-str post'))))


(defn publish! [post-id]
  (let [new?      (str/starts-with? post-id "@")
        draft-dir (io/file (str "grumpy_data/drafts/" post-id))]
    ;; clean up old post
    (when-not new?
      (let [old (grumpy/get-post post-id)]
        (.delete (io/file (str "grumpy_data/posts/" post-id "/post.edn")))
        (doseq [key   [:picture :picture-original]
                :let  [pic (get old key)]
                :when (some? pic)]
          (.delete (io/file (str "grumpy_data/posts/" post-id "/" (:url pic)))))))
    ;; create/update new post
    (let [now  (grumpy/now)
          post (cond-> (get-draft post-id)
                 true (assoc :updated now)
                 new? (assoc :created now
                             :id (next-post-id now))) ;; assign post id
          post-dir (io/file (str "grumpy_data/posts/" (:id post)))]
      ;; create new post dir
      (when new?
        (.mkdirs post-dir))

      ;; move picture
      (doseq [key   [:picture :picture-original]
              :let  [pic (get post key)]
              :when (some? pic)]
        (.renameTo (io/file draft-dir (:url pic)) (io/file post-dir (:url pic))))

      ;; write post.edn
      (spit (io/file post-dir "post.edn") (pr-str post))

      ;; cleanup
      (.delete (io/file draft-dir "post.edn"))
      (.delete draft-dir)

      (if new?
        (grumpy/try-async
          "telegram/post-picture!"
          #(update-post! (:id post) telegram/post-picture!)
          { :after (fn [_] (grumpy/try-async
                             "telegram/post-text!"
                             #(update-post! (:id post) telegram/post-text!))) })
        (grumpy/try-async
          "telegram/update-text!"
          #(telegram/update-text! post)))

      post)))


(defn delete! [post-id]
  (let [dir   (io/file (str "grumpy_data/drafts/" post-id))
        draft (get-draft post-id)]
    (doseq [key   [:picture :picture-original]
            :let  [pic (get draft key)]
            :when (some? pic)]
      (.delete (io/file dir (:url pic))))
    (.delete (io/file dir "post.edn"))
    (.delete dir)))


(rum/defc edit-post-page [post-id user]
  (let [post (or (get-draft (or post-id user))
                 { :body ""
                   :author user })
        new? (str/starts-with? post-id "@")
        data { :new?    new?
               :post-id post-id
               :post    post
               :user    user }]
    (grumpy/page { :title (if new? "Edit draft" "Edit post")
                   :styles ["authors.css"] }
      [:.mount { :data (pr-str data) }
        (editor/editor (assoc data :server? true))]
      [:script { :src (str "/" (grumpy/checksum-resource "static/editor.js")) }]
      [:script { :dangerouslySetInnerHTML { :__html "grumpy.editor.refresh();" }}])))


(def ^:private interceptors [auth/populate-session auth/require-user])


(def routes
  (routes/expand
    [:get "/new"
     interceptors
     (fn [req]
       (let [user (auth/user req)]
         (grumpy/html-response (edit-post-page (str "@" user) user))))]

    [:get "/post/:post-id/edit"
     interceptors
     (fn [{{:keys [post-id]} :path-params :as req}]
       (grumpy/html-response (edit-post-page post-id (auth/user req))))]

   [:post "/post/:post-id/save"
    interceptors
    (fn [{{:keys [post-id]} :path-params, body :body :as req}]
      (let [payload (transit/read-transit body)
            saved   (save-post! post-id (:post payload))]
        (grumpy/transit-response {:post saved})))]

   [:post "/post/:post-id/upload"
    interceptors
    (fn [{{:keys [post-id]} :path-params, body :body :as req}]
      (let [saved (save-picture! post-id (get-in req [:headers "content-type"]) body)]
        (grumpy/transit-response {:post saved})))]

   [:post "/post/:post-id/publish"
    interceptors
    (fn [{{:keys [post-id]} :path-params, body :body :as req}]
      (let [payload (transit/read-transit body)
            _       (save-post! post-id (:post payload))
            post'   (publish! post-id)]
        (grumpy/transit-response {:post post'})))]

   [:post "/draft/:post-id/delete"
    interceptors
    (fn [{{:keys [post-id]} :path-params :as req}]
      (delete! post-id)
      {:status 200})]

   [:get "/post/:post-id/delete"
    interceptors
    (fn [{{:keys [post-id]} :path-params :as req}]
      (grumpy/delete-dir (str "grumpy_data/posts/" post-id))
      (grumpy/redirect "/"))]

   [:get "/draft/:post-id/:img"
    interceptors
    (fn [{{:keys [post-id img]} :path-params}]
      (response/file-response (str "grumpy_data/drafts/" post-id "/" img)))]

   [:post "/post/:post-id/edit"
    interceptors
    middlewares/multipart-params
    (fn [{{:keys [post-id]} :path-params
          {:keys [body picture author]} :multipart-params
          :as req}]
      (save-post!
        {:id     post-id
         :body   body
         :author author}
        (when (some-> picture :size pos?) picture))
      (grumpy/redirect "/"))]))