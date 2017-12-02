(ns grumpy.authors
  (:require
    [rum.core :as rum]
    [clojure.string :as str]
    [clojure.java.io :as io]
    [compojure.core :as compojure]
    [ring.middleware.multipart-params]
    
    [grumpy.core :as grumpy]
    [grumpy.auth :as auth]
    [grumpy.editor :as editor]))


(defn next-post-id [^java.util.Date inst]
  (str
    (grumpy/encode (quot (.getTime inst) 1000) 6)
    (grumpy/encode (rand-int (* 64 64 64)) 3)))


(defn picture-name [post-id picture]
  (when (some? picture)
    (let [in-name  (:filename picture)
          [_ ext]  (re-matches #".*(\.[^\.]+)" in-name)]
      (str post-id "~" (grumpy/encode (System/currentTimeMillis) 7) ext))))


(defn save-post! [post picture]
  (let [post-id       (:id post)
        old-post      (grumpy/get-post post-id)
        dir           (io/file (str "grumpy_data/posts/" post-id))
        override?     (some? picture)
        picture-name  (picture-name post-id picture)]
    (if (nil? old-post)
      (.mkdirs dir)
      (when override?
        (when-some [pic (:picture old-post)]
          (io/delete-file (io/file dir (:url pic))))))
    (when (some? picture)
      (io/copy (:tempfile picture) (io/file dir picture-name))
      (.delete (:tempfile picture)))
    (let [post' (merge
                  { :picture  (if override?
                                { :url picture-name }
                                (:picture old-post))
                    :created  (grumpy/now)
                    :updated  (grumpy/now) }
                  post
                  (select-keys old-post [:created]))]
      (spit (io/file dir "post.edn") (pr-str post')))))


(rum/defc edit-post-page [post-id user]
  (let [post    (grumpy/get-post post-id)
        create? (nil? post)
        data    { :post-id post-id
                  :post    post
                  :user    user }]
    (grumpy/page { :title (if create? "New post" "Edit post")
                   :styles ["authors.css"] }
      [:.mount { :data (pr-str data) }
        (editor/editor data)]
      [:script { :src "/static/editor.js" }]
      [:script { :dangerouslySetInnerHTML { :__html "grumpy.editor.refresh();" }}])))


(compojure/defroutes routes
  (compojure/GET "/new" [:as req]
    (or
      (auth/check-session req)
      (grumpy/redirect (str "/post/" (next-post-id (grumpy/now)) "/edit"))))

  (compojure/GET "/post/:post-id/edit" [post-id :as req]
    (or
      (auth/check-session req)
      (grumpy/html-response (edit-post-page post-id (auth/user req)))))

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
