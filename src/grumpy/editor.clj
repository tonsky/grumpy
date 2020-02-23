(ns grumpy.editor
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [io.pedestal.http.ring-middlewares :as middlewares]
   [grumpy.auth :as auth]
   [grumpy.core.config :as config]
   [grumpy.core.files :as files]
   [grumpy.core.jobs :as jobs]
   [grumpy.core.macros :refer [cond+]]
   [grumpy.core.mime :as mime]
   [grumpy.core.posts :as posts]
   [grumpy.core.routes :as routes]
   [grumpy.core.time :as time]
   [grumpy.core.transit :as transit]
   [grumpy.core.web :as web]
   [grumpy.telegram :as telegram]
   [grumpy.video :as video]
   [ring.util.response :as response]
   [rum.core :as rum])
  (:import
   [java.io File InputStream]))


(defn image-dimensions [^File file]
  (let [out (:out (jobs/sh "convert" (.getPath file) "-ping" "-format" "[%w,%h]" "info:"))
        [w h] (edn/read-string out)]
    [w h]))


(defn convert-image! [^File from ^File to opts]
  (apply jobs/sh "convert"
    (.getPath from)
    (concat
      (flatten
        (for [[k v] opts
              :when (some? v)]
          (if (true? v)
            (str "-" (name k))
            [(str "-" (name k)) (str v)])))
      [(.getPath to)])))


(defn save-picture! [post-id content-type ^InputStream input-stream]
  (let [draft (posts/get-draft post-id)
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
                         prefix   (posts/encode (System/currentTimeMillis) 7)
                         original (io/file dir (str prefix ".orig." ext))]
                     (io/copy input-stream original)
                     (cond+
                       ;; video
                       (mime/video? content-type)
                       (assoc draft
                         :picture
                         { :url          (.getName original)
                           :content-type content-type
                           :dimensions   (video/dimensions original) })
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

                       (not (mime/image? content-type))
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
  (let [draft  (posts/get-draft post-id)
        draft' (merge draft updates)
        dir    (str "grumpy_data/drafts/" post-id)
        file   (io/file dir "post.edn")]
    (spit (io/file file) (pr-str draft'))
    draft'))


(defn update-post! [post-id f]
  (let [post  (posts/get-post post-id)
        post' (f post)]
    (spit (io/file (str "grumpy_data/posts/" post-id) "post.edn") (pr-str post'))))


(defn publish! [post-id]
  (let [new?      (str/starts-with? post-id "@")
        draft-dir (io/file (str "grumpy_data/drafts/" post-id))]
    ;; clean up old post
    (when-not new?
      (let [old (posts/get-post post-id)]
        (.delete (io/file (str "grumpy_data/posts/" post-id "/post.edn")))
        (doseq [key   [:picture :picture-original]
                :let  [pic (get old key)]
                :when (some? pic)]
          (.delete (io/file (str "grumpy_data/posts/" post-id "/" (:url pic)))))))
    ;; create/update new post
    (let [now  (time/now)
          post (cond-> (posts/get-draft post-id)
                 true (assoc :updated now)
                 new? (assoc :created now
                             :id (posts/next-post-id now))) ;; assign post id
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
        (jobs/try-async
          "telegram/post-picture!"
          #(update-post! (:id post) telegram/post-picture!)
          { :after (fn [_] (jobs/try-async
                             "telegram/post-text!"
                             #(update-post! (:id post) telegram/post-text!))) })
        (jobs/try-async
          "telegram/update-text!"
          #(telegram/update-text! post)))

      post)))


(defn delete! [post-id]
  (let [dir   (io/file (str "grumpy_data/drafts/" post-id))
        draft (posts/get-draft post-id)]
    (doseq [key   [:picture :picture-original]
            :let  [pic (get draft key)]
            :when (some? pic)]
      (.delete (io/file dir (:url pic))))
    (.delete (io/file dir "post.edn"))
    (.delete dir)))


(rum/defc edit-post-page [post-id user]
  (let [post (or (posts/get-draft (or post-id user))
                 {:body ""
                  :author user})
        new? (str/starts-with? post-id "@")
        data {:new?    new?
              :post-id post-id
              :post    post
              :user    user}]
    (web/page {:title (if new? "Edit draft" "Edit post")
               :styles ["editor.css"]
               :subtitle? false }
      [:.mount {:data (pr-str data)}
        #_(editor/editor (assoc data :server? true))] ;; TODO
      [:script { :src (str "/" (web/checksum-resource "static/editor.js")) }]
      [:script { :dangerouslySetInnerHTML { :__html "grumpy.editor.refresh();"}}])))


(def ^:private interceptors [auth/populate-session auth/require-user])


(def routes
  (routes/expand
    [:get "/new"
     interceptors
     (fn [req]
       (let [user (auth/user req)]
         (web/html-response (edit-post-page (str "@" user) user))))]

    [:get "/post/:post-id/edit"
     interceptors
     (fn [{{:keys [post-id]} :path-params :as req}]
       (web/html-response (edit-post-page post-id (auth/user req))))]

   [:post "/post/:post-id/save"
    interceptors
    (fn [{{:keys [post-id]} :path-params, body :body :as req}]
      (let [payload (transit/read-transit body)
            saved   (save-post! post-id (:post payload))]
        (web/transit-response {:post saved})))]

   [:post "/post/:post-id/update-body"
    interceptors
    (fn [{{:keys [post-id]} :path-params, body :body :as req}]
      (when config/dev?
        (Thread/sleep 1000)
        (when (> (rand) 0.3)
          (throw (ex-info "Dev simulated exception" {}))))
      (let [payload (transit/read-transit body)
            body    (:body (:post payload))
            draft'  (posts/update-draft! post-id #(assoc % :body body))]
        (web/transit-response {:post draft'})))]

   [:post "/post/:post-id/upload"
    interceptors
    (fn [{{:keys [post-id]} :path-params, body :body :as req}]
      (let [saved (save-picture! post-id (get-in req [:headers "content-type"]) body)]
        (web/transit-response {:post saved})))]

   [:post "/post/:post-id/publish"
    interceptors
    (fn [{{:keys [post-id]} :path-params, body :body :as req}]
      (let [payload (transit/read-transit body)
            _       (save-post! post-id (:post payload))
            post'   (publish! post-id)]
        (web/transit-response {:post post'})))]

   [:post "/draft/:post-id/delete"
    interceptors
    (fn [{{:keys [post-id]} :path-params :as req}]
      (delete! post-id)
      {:status 200})]

   [:get "/post/:post-id/delete"
    interceptors
    (fn [{{:keys [post-id]} :path-params :as req}]
      (files/delete-dir (str "grumpy_data/posts/" post-id))
      (web/redirect "/"))]

   [:get "/draft/:post-id/:img"
    [auth/populate-session]
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
      (web/redirect "/"))]))