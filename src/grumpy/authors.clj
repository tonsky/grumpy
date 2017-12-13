(ns grumpy.authors
  (:require
    [rum.core :as rum]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [clojure.java.io :as io]
    [compojure.core :as compojure]
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


(defn picture-name [post-id picture]
  (when (some? picture)
    (let [in-name  (:filename picture)
          [_ ext]  (re-matches #".*(\.[^\.]+)" in-name)]
      (str post-id "~" (grumpy/encode (System/currentTimeMillis) 7) ext))))


; (defn save-picture [post picture]
;   (let [post-id       (:id post)
;         old-post      (grumpy/get-post post-id)
;         dir           (io/file (str "grumpy_data/posts/" post-id))
;         override?     (some? picture)
;         picture-name  (picture-name post-id picture)]
;     (if (nil? old-post)
;       (.mkdirs dir)
;       (when override?
;         (when-some [pic (:picture old-post)]
;           (io/delete-file (io/file dir (:url pic))))))
;     (when (some? picture)
;       (io/copy (:tempfile picture) (io/file dir picture-name))
;       (.delete (:tempfile picture)))
;     (let [post' (merge
;                   { :picture  (if override?
;                                 { :url picture-name }
;                                 (:picture old-post))
;                     :created  (grumpy/now)
;                     :updated  (grumpy/now) }
;                   post
;                   (select-keys old-post [:created]))]
;       (spit (io/file dir "post.edn") (pr-str post')))))


(defn save-post! [post-id post]
  (let [post' (merge
                { :created (grumpy/now) }
                post
                { :updated (grumpy/now) })]
    (spit (io/file (str "grumpy_data/drafts/" post-id "/post.edn"))
      (pr-str post'))
    post'))


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
