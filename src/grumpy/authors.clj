(ns grumpy.authors
  (:require
    [rum.core :as rum]
    [clojure.string :as str]
    [clojure.java.io :as io]
    [compojure.core :as compojure]
    [ring.middleware.multipart-params]
    
    [grumpy.core :as grumpy]
    [grumpy.auth :as auth]))


(defn next-post-id [^java.util.Date inst]
  (str
    (grumpy/encode (quot (.getTime inst) 1000) 6)
    (grumpy/encode (rand-int (* 64 64 64)) 3)))


(defn save-post! [post pictures {:keys [delete?]}]
  (let [id            (:id post)
        old-post      (grumpy/get-post id)
        dir           (io/file (str "grumpy_data/posts/" id))
        picture-names (for [[picture idx] (grumpy/zip pictures (range))
                            :let [in-name  (:filename picture)
                                  [_ ext]  (re-matches #".*(\.[^\.]+)" in-name)]]
                        (str id "_" (inc idx) ext))]
    (if (nil? old-post)
      (.mkdirs dir)
      (when (not-empty picture-names)
       (doseq [name (:pictures old-post)]
         (io/delete-file (io/file dir name)))))
    (doseq [[picture name] (grumpy/zip pictures picture-names)]
      (io/copy (:tempfile picture) (io/file dir name))
      (when delete?
        (.delete (:tempfile picture))))
    (let [post' (merge
                  { :pictures (or (not-empty (vec picture-names))
                                  (:pictures old-post))
                    :created  (grumpy/now)
                    :updated  (grumpy/now) }
                  post
                  (select-keys old-post [:created]))]
      (spit (io/file dir "post.edn") (pr-str post')))))


(rum/defc edit-post-page [post-id user]
  (let [post    (grumpy/get-post post-id)
        create? (nil? post)]
    (grumpy/page { :title (if create? "New post" "Edit post")
                   :styles ["authors.css"] }
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
              :placeholder "Be grumpy here..."
              :autofocus true }]]
        [:.form_row
          "Author: " [:input.edit-post_author { :type "text" :name "author" :value (or (:author post) user) }]]
        [:.form_row
          [:button (if create? "Grumpost now!" "Edit")]]]
      [:script { :src "/static/editor.js" }])))


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
                        [picture])
                      { :delete? true })
          (grumpy/redirect "/"))))))
