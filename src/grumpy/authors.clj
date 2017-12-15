(ns grumpy.authors
  (:require
    [rum.core :as rum]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [clojure.java.io :as io]
    [compojure.core :as compojure]
    [ring.util.response]
    [ring.middleware.multipart-params]
    
    [grumpy.core :as grumpy]
    [grumpy.auth :as auth]
    [grumpy.editor :as editor]
    [grumpy.transit :as transit]))


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


(defn save-picture! [post-id content-type input-stream]
  (let [draft (get-draft post-id)
        dir   (io/file (str "grumpy_data/drafts/" post-id))]
    (when-some [picture (:picture draft)]
      (let [file (io/file dir (:url picture))]
        (when (.exists file)
          (io/delete-file file))))
    (let [draft' (if (some? input-stream)
                   (let [[_ ext] (str/split content-type #"/")
                         name    (str (grumpy/encode (System/currentTimeMillis) 7) "." ext)
                         picture { :url name
                                   :content-type content-type }]
                     (io/copy input-stream (io/file dir name))
                     (assoc draft :picture picture))
                   (dissoc draft :picture))]
      (spit (io/file dir "post.edn") draft')
      draft')))


(defn save-post! [post-id updates]
  (let [draft  (get-draft post-id)
        draft' (merge draft updates)
        dir    (str "grumpy_data/drafts/" post-id)
        file   (io/file dir "post.edn")]
    (spit (io/file file) (pr-str draft'))
    draft'))


(defn publish! [post-id]
  (let [create?   (str/starts-with? post-id "@")
        draft-dir (io/file (str "grumpy_data/drafts/" post-id))]
    ;; clean up old post
    (when-not create?
      (let [old (grumpy/get-post post-id)]
        (.delete (io/file (str "grumpy_data/posts/" post-id "/post.edn")))
        (when-some [pic (:picture old)]
          (.delete (io/file (str "grumpy_data/posts/" post-id "/" (:url pic)))))))
    ;; create/update new post
    (let [now  (grumpy/now)
          post (cond-> (get-draft post-id)
                 true    (assoc :updated now)
                 create? (assoc :created now
                                :id (next-post-id now))) ;; assign post id
          post-dir (io/file (str "grumpy_data/posts/" (:id post)))]
      ;; create new post dir
      (when create?
        (.mkdirs post-dir))
      ;; write post.edn
      (spit (io/file post-dir "post.edn") (pr-str post))
      ;; move picture
      (when-some [pic (:picture post)]
        (.renameTo (io/file draft-dir (:url pic)) (io/file post-dir (:url pic))))
      ;; cleanup
      (.delete (io/file draft-dir "post.edn"))
      (.delete draft-dir)
      post)))


(rum/defc edit-post-page [post-id user]
  (let [post    (or (get-draft (or post-id user))
                    { :body ""
                      :author user })
        create? (str/starts-with? post-id "@")
        data    { :create? create?
                  :post-id post-id
                  :post    post
                  :user    user }]
    (grumpy/page { :title (if create? "Edit draft" "Edit post")
                   :styles ["authors.css"] }
      [:.mount { :data (pr-str data) }
        (editor/editor data)]
      [:script { :src "/static/editor.js" }]
      [:script { :dangerouslySetInnerHTML { :__html "grumpy.editor.refresh();" }}])))


(compojure/defroutes routes
  (compojure/GET "/new" [:as req]
    (or
      (auth/check-session req)
      (grumpy/html-response (edit-post-page (str "@" (auth/user req)) (auth/user req)))))

  (compojure/GET "/post/:post-id/edit" [post-id :as req]
    (or
      (auth/check-session req)
      (grumpy/html-response (edit-post-page post-id (auth/user req)))))
  
  (compojure/POST "/post/:post-id/save" [post-id :as req]
    (or
      (auth/check-session req)
      (let [payload (-> (:body req)
                        (transit/read-transit))
            saved   (save-post! post-id (:post payload))]
        { :body (transit/write-transit-str { :post saved }) })))

  (compojure/POST "/post/:post-id/upload" [post-id :as req]
    (or
      (auth/check-session req)
      (let [payload (:body req)
            saved   (save-picture! post-id (get-in req [:headers "content-type"]) payload)]
        { :body (transit/write-transit-str { :post saved }) })))

  (compojure/POST "/post/:post-id/publish" [post-id :as req]
    (or
      (auth/check-session req)
      (let [payload (transit/read-transit (:body req))
            _       (save-post! post-id (:post payload))
            post'   (publish! post-id)]
      { :body (transit/write-transit-str { :post post' })})))

  (compojure/GET "/draft/:post-id/:img" [post-id img]
    (ring.util.response/file-response (str "grumpy_data/drafts/" post-id "/" img)))

  (ring.middleware.multipart-params/wrap-multipart-params
    (compojure/POST "/post/:post-id/edit" [post-id :as req]
      (or
        (auth/check-session req)
        (let [params  (:multipart-params req)
              body    (get params "body")
              picture (get params "picture")]
          (save-post! { :id     post-id
                        :body   body
                        :author (get params "author") }
                      (when (some-> picture :size pos?)
                        picture))
          (grumpy/redirect "/"))))))
