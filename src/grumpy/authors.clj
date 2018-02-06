(ns grumpy.authors
  (:require
    [rum.core :as rum]
    [clojure.edn :as edn]
    [clojure.java.shell :as shell]
    [clojure.string :as str]
    [clojure.java.io :as io]
    [clojure.stacktrace :as stacktrace]
    [compojure.core :as compojure]
    [ring.util.response]
    [ring.middleware.multipart-params]
    
    [grumpy.core :as grumpy]
    [grumpy.auth :as auth]
    [grumpy.editor :as editor]
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
  (let [out (:out (shell/sh "convert" (.getPath file) "-ping" "-format" "[%w,%h]" "info:"))
        [w h] (edn/read-string out)]
    [w h]))


(defn save-picture! [post-id content-type input-stream]
  (let [draft (get-draft post-id)
        dir   (io/file (str "grumpy_data/drafts/" post-id))]
    (doseq [key   [:picture :picture-original]
            :let  [picture (get draft key)]
            :when (some? picture)
            :let  [file (io/file dir (:url picture))]
            :when (.exists file)]
      (io/delete-file file))
    (let [draft' (if (some? input-stream)
                   (let [[_ ext]  (str/split content-type #"/")
                         prefix   (grumpy/encode (System/currentTimeMillis) 7)
                         original (io/file dir (str prefix "." ext))
                         _        (io/copy input-stream original)
                         image?   (str/starts-with? content-type "image/")
                         [w h]    (when image? (image-dimensions original))
                         resize?  (and image? (or (> w 1100) (> h 1000)))]
                     (cond
                       ;; video
                       (not image?)
                       (assoc draft
                         :picture { :url (.getName original)
                                    :content-type content-type })
                       
                       ;; small jpeg
                       (and (= "image/jpeg" content-type) (not resize?))
                       (assoc draft
                         :picture { :url (.getName original)
                                    :content-type content-type
                                    :dimensions [w h] })

                       :else
                       (let [converted (io/file dir (str prefix ".fit.jpeg"))]
                         (if resize?
                           (shell/sh "convert" (.getPath original) "-resize" "1100x1000" "-quality" "85" (.getPath converted))
                           (shell/sh "convert" (.getPath original) "-quality" "85" (.getPath converted)))
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

      (let [post' (try
                    (if new?
                      (-> post (telegram/post-picture!) (telegram/post-text!))
                      (-> post (telegram/update-text!)))
                    (catch Exception e
                      (println "Telegram post error:" (pr-str (ex-data e)))
                      (stacktrace/print-stack-trace (stacktrace/root-cause e))
                      post))]
        ;; write post.edn
        (spit (io/file post-dir "post.edn") (pr-str post'))
      
        ;; cleanup
        (.delete (io/file draft-dir "post.edn"))
        (.delete draft-dir)
        post'))))


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

  (compojure/POST "/draft/:post-id/delete" [post-id :as req]
    (or
      (auth/check-session req)
      (do 
        (delete! post-id)
        { :status 200 })))

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
