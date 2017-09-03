(ns grumpy.authors
  (:require
    [rum.core :as rum]
    [clojure.string :as str]
    [clojure.java.io :as io]
    [compojure.core :as compojure]
    [ring.middleware.multipart-params]
    
    [grumpy.core :as grumpy]
    [grumpy.auth :as auth]))


(defn next-post-id []
  (str
    (grumpy/encode (quot (System/currentTimeMillis) 1000) 6)
    (grumpy/encode (rand-int (* 64 64 64)) 3)))


(defn save-post! [post pictures]
  (let [id            (:id post)
        dir           (io/file (str "grumpy_data/posts/" id))
        picture-names (for [[picture idx] (grumpy/zip pictures (range))
                            :let [in-name  (:filename picture)
                                  [_ ext]  (re-matches #".*(\.[^\.]+)" in-name)]]
                        (str id "_" (inc idx) ext))]
    (.mkdirs dir)
    (doseq [[picture name] (grumpy/zip pictures picture-names)]
      (io/copy (:tempfile picture) (io/file dir name))
      (.delete (:tempfile picture)))
    (let [old-post (grumpy/get-post id)
          post'    (merge post
                     { :pictures (vec picture-names)
                       :created  (grumpy/now)
                       :updated  (grumpy/now) }
                     (select-keys old-post [:created]))]
      (spit (io/file dir "post.edn") (pr-str post')))))


(rum/defc edit-post-page [post-id]
  (let [post    (grumpy/get-post post-id)
        create? (nil? post)]
    (grumpy/page { :title (if create? "Новый пост" "Правка поста")
                   :styles "authors.css" }
      [:form.edit-post
        { :action (str "/post/" post-id "/edit")
          :enctype "multipart/form-data"
          :method "post" }
        [:.form_row.edit-post_picture
          [:input { :type "file" :name "picture"}]]
        [:.form_row
          [:textarea
            { :value (:body post "")
              :name "body"
              :placeholder "Пиши сюда..."
              :autofocus true }]]
        [:.form_row
          [:button (if create? "В печать!" "Поправить")]]])))


(compojure/defroutes routes
  (compojure/GET "/new" [:as req]
    (or
      (auth/check-session req)
      (grumpy/redirect (str "/post/" (next-post-id) "/edit"))))

  (compojure/GET "/post/:post-id/edit" [post-id :as req]
    (or
      (auth/check-session req)
      (grumpy/html-response (edit-post-page post-id))))

  (ring.middleware.multipart-params/wrap-multipart-params
    (compojure/POST "/post/:post-id/edit" [post-id :as req]
      (or
        (auth/check-session req)
        (let [params  (:multipart-params req)
              body    (get params "body")
              picture (get params "picture")]
          (save-post! { :id      post-id
                        :body    body
                        :author  (get-in req [:session :user]) }
                      [picture])
          (grumpy/redirect "/"))))))