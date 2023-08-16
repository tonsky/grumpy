(ns grumpy.editor
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [datascript.core :as d]
    [io.pedestal.http.ring-middlewares :as middlewares]
    [grumpy.auth :as auth]
    [grumpy.core.coll :as coll]
    [grumpy.core.config :as config]
    [grumpy.core.files :as files]
    [grumpy.core.fragments :as fragments]
    [grumpy.core.jobs :as jobs]
    [grumpy.core.log :as log]
    [grumpy.core.macros :refer [cond+]]
    [grumpy.core.mime :as mime]
    [grumpy.core.posts :as posts]
    [grumpy.core.routes :as routes]
    [grumpy.core.time :as time]
    [grumpy.core.transit :as transit]
    [grumpy.core.web :as web]
    [grumpy.db :as db]
    [grumpy.telegram :as telegram]
    [grumpy.video :as video]
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


(defn convert-media! [^File original mime-type]
  (cond+
    :let [original-name (.getName original)]

    ;; video
    (mime/video? mime-type)
    (let [[w h]          (video/dimensions original)
          converted-name (str/replace original-name #"^([^.]+)\.orig\.[a-z0-9]+$" "$1.fit.mp4")
          converted      (io/file (.getParentFile original) converted-name)]
      (video/local-convert! original converted [w h])
      {:picture
       {:url          converted-name
        :content-type "video/mp4"
        :dimensions   (video/dimensions converted)}
       :picture-original 
       {:url          original-name
        :content-type mime-type
        :dimensions   [w h]}})

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
          converted      (io/file (.getParentFile original) converted-name)]
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


; (defn upload-media! [post-id mime-type ^InputStream input-stream]
;   (jobs/linearize post-id
;     (drafts/delete-media! post-id)
;     (let [dir      (io/file "grumpy_data/drafts" post-id)
;           name     (posts/encode (System/currentTimeMillis) 7)
;           ext      (mime/extension mime-type)
;           original (io/file dir (str name ".orig." ext))
;           _        (io/copy input-stream original)
;           updates  (convert-media! original mime-type)]
;       (drafts/update! post-id #(merge % updates))
;       updates)))


(defn publish! [post-id body]  
  (let [now  (time/now)
        post (if (nil? post-id)
               {:post/id      (posts/next-id (db/db))
                :post/body    (:post/body body)
                :post/author  (:post/author body)
                :post/created now
                :post/updated now}
               {:post/id   post-id
                :post/body (:post/body body)
                :post/updated now})]
    ;; create/update new post
      
    (d/transact db/conn [post])
      
    ; ;; create new post dir
    ; (when new?
    ;   (.mkdirs post-dir))

    ; ;; move picture
    ; (doseq [key   [:picture :picture-original]
    ;         :let  [pic (get post key)]
    ;         :when (some? pic)]
    ;   (.renameTo (io/file draft-dir (:url pic)) (io/file post-dir (:url pic))))

    ;; notify telegram
    ; (if new?
    ;   (jobs/try-async "telegram/post-picture!"
    ;     #(posts/update! post-id' telegram/post-picture!)
    ;     {:after (fn [_]
    ;               (jobs/try-async "telegram/post-text!"
    ;                 #(posts/update! post-id' telegram/post-text!)))})
    ;   (jobs/try-async "telegram/update-text!"
    ;     #(telegram/update-text! post)))

    post))


(rum/defc edit-draft-page [post-id user]
  (let [db   (db/db)
        post (if post-id
               (d/pull db '[:post/id :post/author :post/body {:post/media [*]}] [:post/id post-id])
               {:post/author user
                :post/body   ""})]
    (web/page {:title     (if post-id "New post" "Edit post")
               :styles    ["editor.css"]
               :subtitle? false}
      [:.mount {:data (pr-str post)}] ;; TODO transit?
      [:script {:src (str "/" (web/checksum-resource "static/editor.js"))}]
      [:script {:dangerouslySetInnerHTML {:__html "grumpy.editor.refresh();"}}])))


(def ^:private interceptors
  [auth/populate-session auth/require-user])


(def routes
  (routes/expand
    [:get "/new"
     interceptors
     (fn [req]
       (let [user (auth/user req)]
         (web/html-response
           (edit-draft-page nil user))))]
    
    [:post "/new"
     interceptors
     (fn [req]
       (let [body (-> req :body slurp edn/read-string)
             post (publish! nil body)]
         (log/log "Published" (:post/id post))
         (web/transit-response post)))]

    [:get "/post/:post-id/edit"
     interceptors
     (fn [req]
       (let [post-id (-> (:path-params req) :post-id parse-long)]
         (web/html-response
           (edit-draft-page post-id (auth/user req)))))]
    
    [:post "/post/:post-id/edit"
     interceptors
     (fn [req]
       (let [post-id (-> (:path-params req) :post-id parse-long)
             body    (-> req :body slurp edn/read-string)
             post    (publish! post-id body)]
         (log/log "Updated" (:post/id post))
         (web/transit-response post)))]

    ;  [:post "/draft/:post-id/upload-media"
    ;   interceptors
    ;   (fn [{{:keys [post-id]} :path-params
    ;         {:strs [content-type]} :headers
    ;         request-body :body}]
    ;     ; (when (and config/dev? (> (rand) 0.666667))
    ;     ;   (throw (ex-info (str "/draft/" post-id "/upload-media simulated exception") {})))
    ;     (log/log (str "Uploading " content-type " to /draft/" post-id "..."))
    ;     (let [updates (upload-media! post-id content-type request-body)]
    ;       (log/log (str "Uploaded " content-type " to /draft/" post-id " as " (pr-str updates)))
    ;       (web/transit-response updates)))]

    ;  [:post "/draft/:post-id/publish"
    ;   interceptors
    ;   (fn [{{:keys [post-id]} :path-params, request-body :body :as req}]
    ;     ; (Thread/sleep 1000)
    ;     (log/log (str "Publishing /draft/" post-id "..."))
    ;     (let [body  (slurp request-body)
    ;           post' (jobs/linearize post-id
    ;                   (drafts/update! post-id #(assoc % :body body))
    ;                   (publish! post-id))]
    ;       (log/log (str "Published /draft/" post-id " as /post/" (:id post')))
    ;       (web/transit-response post')))]

    [:get "/post/:post-id/delete"
     interceptors
     (fn [req]
       (let [post-id (-> (:path-params req) :post-id parse-long)]
         (log/log (str "Deleting /post/" post-id))
         (posts/delete! post-id)
         (web/redirect "/")))]
    
    ))
