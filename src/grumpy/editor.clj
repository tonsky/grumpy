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
    [ring.util.response :as response]
    [rum.core :as rum])
  (:import
    [java.io File InputStream]))

(defn image-dimensions [^File file]
  (let [out   (:out (jobs/sh "convert" (.getPath file) "-ping" "-format" "[%w,%h]" "info:"))
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

(defn convert-media! [^File full mime-type]
  (cond+
    :let [dir       "uploads/"
          full-name (.getName full)]

    ;; video
    (mime/video? mime-type)
    (let [[w h]          (video/dimensions full)
          converted-name (str/replace full-name #"^(.+)_full\.[a-z0-9]+$" "$1.mp4")
          converted      (io/file (.getParentFile full) converted-name)
          _              (video/local-convert! full converted [w h])
          [w' h']        (video/dimensions converted)]
      {:post/media
       {:media/url          (str dir converted-name)
        :media/content-type "video/mp4"
        :media/width        w'
        :media/height       h'}
       :post/media-full 
       {:media/url          (str dir full-name)
        :media/content-type mime-type
        :media/width        w
        :media/height       h}})

    ;; neither image nor video
    (not (mime/image? mime-type))
    (throw (ex-info (str "Unknown content-type: " mime-type) {:mime-type mime-type}))

    :let [[w h] (image-dimensions full)
          resize? (or (> w 1100) (> h 1000))]

    ;; gif or small jpeg
    (or
      (= "image/gif" mime-type)
      (and
        (= "image/jpeg" mime-type)
        (not resize?)))
    (let [converted-name (str/replace full-name #"^(.+)_full\.([a-z]+)$" "$1.$2")
          converted      (io/file (.getParentFile full) converted-name)]
      (files/move full converted)
      {:post/media
       {:media/url          (str dir converted-name)
        :media/content-type mime-type
        :media/width        w
        :media/height       h}})

    ;; png, large jpeg, etc
    :else
    (let [converted-name (str/replace full-name #"^(.+)_full\.[a-z]+$" "$1.jpeg")
          converted      (io/file (.getParentFile full) converted-name)
          _              (convert-image! full converted
                           {:quality 85
                            :flatten true
                            :resize  (when resize? "1100x1000")})
          [w' h']        (image-dimensions converted)]
      {:post/media
       {:media/url           (str dir converted-name)
        :media/content-type  "image/jpeg"
        :media/width         w'
        :media/height        h'}
       :post/media-full
       {:media/url          (str dir full-name)
        :media/content-type mime-type
        :media/width        w
        :media/height       h}})))

(defn upload-media! [mime-type ^InputStream input-stream]
  (let [name    (str (posts/next-id (db/db)) "_" (time/format-timestamp-inst (time/now)))
        ext     (mime/extension mime-type)
        dir     (io/file "grumpy_data/uploads")
        _       (.mkdirs dir)
        full    (io/file dir (str name "_full." ext))
        _       (io/copy input-stream full)
        updates (convert-media! full mime-type)]
    updates))

(defn publish! [post-id body]
  (let [now       (time/now)
        db        (db/db)
        new?      (nil? post-id)
        post-id   (if new? (posts/next-id db) post-id)
        before    (d/entity db [:post/id post-id])
        
        ;; move media
        year      (time/year (time/now))
        version   (-> before :post/media :media/version (or 0) (inc))
        url-fn    #(when-some [media (% body)]
                     (let [url (:media/url media)]
                       (when (str/starts-with? (:media/url media) "uploads/")
                         (format "%d/%d_%d.%s" year post-id version (files/extension url)))))
        
        media-url (url-fn :post/media)        
        media'    (when media-url
                    (files/move
                      (io/file "grumpy_data" (-> body :post/media :media/url))
                      (io/file "grumpy_data" media-url))
                    (assoc (:post/media body)
                      :db/id         -2
                      :media/version version
                      :media/url     media-url))
        full-url  (url-fn :post/media-full)
        full'     (when full-url
                    (files/move
                      (io/file "grumpy_data" (-> body :post/media-full :media/url))
                      (io/file "grumpy_data" full-url))
                    (assoc (:post/media-full body)
                      :db/id         -3
                      :media/version version
                      :media/url     full-url))

        ;; save post
        tx        (concat
                    ;; updates
                    [{:db/id        -1
                      :post/id      post-id
                      :post/body    (:post/body body)
                      :post/updated now}]
                    ;; new post
                    (when new?
                      [{:db/id        -1
                        :post/created now
                        :post/author  (:post/author body)}])
                    ;; kill old media
                    (when-some [media (:post/media before)]
                      (when (not= (-> body :post/media :media/url) (:media/url media))
                        [[:db.fn/retractAttribute (:db/id before) :post/media]
                         [:db.fn/retractEntity    (:db/id media)]]))
                    ;; add new media
                    (when media-url                        
                      [media'
                       [:db/add -1 :post/media -2]])
                    ;; kill old media-full
                    (when-some [media (:post/media-full before)]
                      (when (not= (-> body :post/media-full :media/url) (:media/url media))
                        [[:db.fn/retractAttribute (:db/id before) :post/media-full]
                         [:db.fn/retractEntity    (:db/id media)]]))
                    ;; add new media-full
                    (when full-url
                      [full'
                       [:db/add -1 :post/media -3]]))
        _         (log/log (str "Transacting\n"
                             (with-out-str (clojure.pprint/pprint tx))))
        report    (d/transact! db/conn tx)
        post      (d/pull (:db-after report) '[*] (get (:tempids report) -1))]
    

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

(rum/defc edit-page [post-id user]
  (let [db   (db/db)
        post (if post-id
               (d/pull db '[:post/id :post/author :post/body {:post/media [*]} {:post/media-full [*]}] [:post/id post-id])
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
           (edit-page nil user))))]
    
    [:post "/new"
     interceptors
     (fn [req]
       (let [body (-> req :body slurp edn/read-string)
             post (publish! nil body)]
         (log/log "Published" (:post/id post))
         (web/transit-response post)))]

    [:get "/:post-id/edit"
     interceptors
     (fn [req]
       (let [post-id (-> (:path-params req) :post-id parse-long)]
         (web/html-response
           (edit-page post-id (auth/user req)))))]
    
    [:post "/:post-id/edit"
     interceptors
     (fn [req]
       (let [post-id (-> (:path-params req) :post-id parse-long)
             body    (-> req :body slurp edn/read-string)
             post    (publish! post-id body)]
         (log/log "Updated" (:post/id post))
         (web/transit-response post)))]
    
    [:get "/:post-id/delete"
     interceptors
     (fn [req]
       (let [post-id (-> (:path-params req) :post-id parse-long)]
         (posts/delete! post-id)
         (web/redirect "/")))]

    [:get "/media/uploads/*path"
     (fn [{{:keys [path]} :path-params}]
       (response/file-response (str "grumpy_data/uploads/" path)))]
    
    [:post "/media/uploads"
     interceptors
     (fn [{{:strs [content-type]} :headers
           request-body :body}]
       ; (when (and config/dev? (> (rand) 0.666667))
       ;   (throw (ex-info (str "/draft/" post-id "/upload-media simulated exception") {})))
       (log/log (str "Uploading " content-type))
       (let [updates (upload-media! content-type request-body)]
         (when-some [media (:post/media-full updates)]
           (log/log (str "Uploaded " content-type " to " (:media/url media))))
         (when-some [media (:post/media updates)]
           (if (:post/media-full updates)
             (log/log (str "Converted " (-> updates :post/media-full :media/url) " to " (:media/url media)))
             (log/log (str "Uploaded " content-type " to " (:media/url media)))))
         (web/transit-response updates)))]))
