(ns grumpy.editor
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [io.pedestal.http.ring-middlewares :as middlewares]
   [grumpy.auth :as auth]
   [grumpy.core.config :as config]
   [grumpy.core.drafts :as drafts]
   [grumpy.core.files :as files]
   [grumpy.core.fragments :as fragments]
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


(defn convert-media! [^File original]
  (cond+
    :let [original-name (.getName original)
          mime-type     (mime/mime-type original-name)]

    ;; video
    (mime/video? mime-type)
    {:picture {:url          original-name
               :content-type mime-type
               :dimensions   (video/dimensions original)}}

    ;; neither image nor video
    (not (mime/image? mime-type))
    (throw (ex-info (str "Unknown content-type: " mime-type) {:mime-type mime-type}))

    :let [[w h] (image-dimensions original)]

    ;; gif
    (= "image/gif" mime-type)
    {:picture {:url          original-name
               :content-type mime-type
               :dimensions   [w h]}}

   :let [resize? (or (> w 1100) (> h 1000))]

   ;; small jpeg
   (and (= "image/jpeg" mime-type) (not resize?))
   {:picture {:url          original-name
              :content-type mime-type
              :dimensions   [w h]}}

   ;; png, large jpeg, etc
   :else
   (let [converted-name (str/replace original-name #"^([^.]+)\.orig\.[a-z]+$" "$1.fit.jpeg")
         converted (io/file (.getParentFile original) converted-name)]
     (convert-image! original converted
       {:quality 85
        :flatten true
        :resize  (when resize? "1100x1000")})
     {:picture
      {:url           converted-name
       :content-type  "image/jpeg"
       :dimensions    (image-dimensions converted)}
      :picture-original
      {:url          original-name
       :content-type mime-type
       :dimensions   [w h]}})))


(defn upload-media! [post-id content-type ^InputStream input-stream]
  (jobs/linearize post-id
    (drafts/delete-media! post-id)
    (let [dir      (io/file "grumpy_data/drafts" post-id)
          name     (posts/encode (System/currentTimeMillis) 7)
          [_ ext]  (str/split content-type #"/")
          original (io/file dir (str name ".orig." ext))
          _        (io/copy input-stream original)
          updates  (convert-media! original)]
      (drafts/update! post-id #(merge % updates))
      updates)))


(defn save-post! [post-id updates]
  (let [draft  (drafts/load post-id)
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
          post (cond-> (drafts/load post-id)
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


(rum/defc edit-draft-page [post-id user]
  (let [new? (fragments/new? post-id)
        post (jobs/linearize post-id
               (or (drafts/load post-id)
                 (if new?
                   (drafts/create-new user)
                   (drafts/create-edit post-id))))]
    (web/page {:title     (if new? "Edit draft" "Edit post")
               :styles    ["editor.css"]
               :subtitle? false}
      [:.mount {:data (pr-str post)}] ;; TODO transit?
      [:script {:src (str "/" (web/checksum-resource "static/editor.js"))}]
      [:script {:dangerouslySetInnerHTML {:__html "grumpy.editor.refresh();"}}])))


(def ^:private interceptors [auth/populate-session auth/require-user])


(def routes
  (routes/expand
    [:get "/new"
     interceptors
     (fn [req]
       (let [user (auth/user req)]
         (web/html-response (edit-draft-page (str "@" user) user))))]

    [:get "/post/:post-id/edit"
     interceptors
     (fn [{{:keys [post-id]} :path-params :as req}]
       (web/html-response (edit-draft-page post-id (auth/user req))))]

    [:post "/draft/:post-id/update-body"
     interceptors
     (fn [{{:keys [post-id]} :path-params, request-body :body}]
       (when config/dev?
         (Thread/sleep 1000)
         (when (> (rand) 0.666667)
           (throw (ex-info (str "/draft/" post-id "/update-body simulated exception") {}))))
       (let [body (slurp request-body)]
         (drafts/update! post-id #(assoc % :body body))
         {:status  200}))]

    [:post "/draft/:post-id/upload-media"
     interceptors
     (fn [{{:keys [post-id]} :path-params, request-body :body :as req}]
       (when (and config/dev? (> (rand) 0.666667))
         (throw (ex-info (str "/draft/" post-id "/upload-media simulated exception") {})))
       (let [updates (upload-media! post-id (get-in req [:headers "content-type"]) request-body)]
         (web/transit-response updates)))]

    [:post "/draft/:post-id/delete-media"
     interceptors
     (fn [{{:keys [post-id]} :path-params}]
       (when (and config/dev? (> (rand) 0.666667))
         (throw (ex-info (str "/draft/" post-id "/delete-media simulated exception") {})))
       (drafts/delete-media! post-id)
       {:status 200})]

   [:post "/post/:post-id/save"
    interceptors
    (fn [{{:keys [post-id]} :path-params, request-body :body :as req}]
      (let [payload (transit/read-transit request-body)
            saved   (save-post! post-id (:post payload))]
        (web/transit-response {:post saved})))]

   [:post "/post/:post-id/publish"
    interceptors
    (fn [{{:keys [post-id]} :path-params, request-body :body :as req}]
      (let [payload (transit/read-transit request-body)
            _       (save-post! post-id (:post payload))
            post'   (publish! post-id)]
        (web/transit-response {:post post'})))]

   [:post "/draft/:post-id/delete"
    interceptors
    (fn [{{:keys [post-id]} :path-params :as req}]
      (drafts/delete! post-id)
      {:status 200})]

   [:get "/post/:post-id/delete"
    interceptors
    (fn [{{:keys [post-id]} :path-params :as req}]
      (posts/delete! post-id)
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