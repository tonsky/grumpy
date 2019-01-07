(ns grumpy.server
  (:require
    [compojure.route]
    [rum.core :as rum]
    [clojure.stacktrace]
    [ring.util.response]
    [immutant.web :as web]
    [clojure.string :as str]
    [ring.middleware.params]
    [compojure.core :as compojure]

    [grumpy.db :as db]
    [grumpy.core :as grumpy]
    [grumpy.auth :as auth]
    [grumpy.authors :as authors]
    [grumpy.feed :as feed])
  (:import
    [java.util Date])
  (:gen-class))


(def page-size 5)


(rum/defc post [post]
  [:.post
    { :data-id (:id post) }
    [:.post_side
      [:img.post_avatar
        { :src (if (some? (grumpy/author-by :user (:author post)))
                 (str "/static/" (:author post) ".jpg")
                 "/static/guest.jpg")}]]
    [:.post_content
      (when-some [pic (:picture post)]
        (let [src (str "/post/" (:id post) "/" (:url pic))
              href (if-some [orig (:picture-original post)]
                     (str "/post/" (:id post) "/" (:url orig))
                     src)]
          (case (grumpy/content-type pic)
            :content.type/video
              [:.post_video_outer
                [:video.post_video
                  { :autoplay true
                    :muted true
                    :loop true
                    :preload "auto"
                    :playsinline true
                    :onplay "toggle_video(this.parentNode, true);" }
                  [:source { :type (grumpy/mime-type (:url pic)) :src src }]]
                [:.post_video_overlay.post_video_overlay-paused { :onclick "toggle_video(this.parentNode);"}]]
            :content.type/image
              (if-some [[w h] (:dimensions pic)]
                (let [[w' h'] (grumpy/fit w h 550 500)]
                  [:div { :style { :max-width w' }}
                    [:a.post_img.post_img-fix
                      { :href href
                        :target "_blank"
                        :style { :padding-bottom (-> (/ h w) (* 100) (double) (str "%")) }}
                      [:img { :src src }]]])
                [:a.post_img.post_img-flex { :href href, :target "_blank" }
                  [:img { :src src }]]))))
      [:.post_body
        { :dangerouslySetInnerHTML
          { :__html (grumpy/format-text
                      (str "<span class=\"post_author\">" (:author post) ": </span>" (:body post))) }}]
      [:p.post_meta
        (grumpy/format-date (:created post))
        " // " [:a {:href (str "/post/" (:id post))} "Hyperlink"]
        [:a.post_meta_edit {:href (str "/post/" (:id post) "/edit")} "Edit"]]]])


(rum/defc index-page [post-ids]
  (grumpy/page {:page :index :scripts ["loader.js"]}
    (for [post-id post-ids]
      (post (grumpy/get-post post-id)))))


(rum/defc posts-fragment [post-ids]
  (for [post-id post-ids]
    (post (grumpy/get-post post-id))))


(rum/defc post-page [post-id]
  (grumpy/page {:page :post}
    (post (grumpy/get-post post-id))))


(compojure/defroutes routes
  (compojure/GET "/post/:post-id/:img" [post-id img]
    (ring.util.response/file-response (str "grumpy_data/posts/" post-id "/" img)))

  (compojure/GET "/post/:post-id" [post-id]
    (grumpy/html-response (post-page post-id)))

  (compojure/GET "/after/:post-id" [post-id]
    (when grumpy/dev? (Thread/sleep 2000))
    (if (and grumpy/dev? (< (rand) 0.5))
      { :status 500 }
      (let [post-ids (->> (grumpy/post-ids)
                          (drop-while #(not= % post-id))
                          (drop 1)
                          (take page-size))]
        { :status  200
          :headers { "Content-Type" "text/html; charset=utf-8" }
          :body    (rum/render-static-markup (posts-fragment post-ids)) })))

  (compojure/GET "/feed.xml" []
    { :status 200
      :headers { "Content-type" "application/atom+xml; charset=utf-8" }
      :body (feed/feed (take 10 (grumpy/post-ids))) })

  (compojure/GET "/sitemap.xml" []
    { :status 200
      :headers { "Content-type" "text/xml; charset=utf-8" }
      :body (feed/sitemap (grumpy/post-ids)) })

  (compojure/GET "/robots.txt" []
    { :status 200
      :headers { "Content-type" "text/plain" }
      :body (grumpy/resource "robots.txt") })

  (auth/wrap-session
    (compojure/routes
      #'auth/routes
      #'authors/routes))
  
  (cond->
    (compojure/GET "/" []
      (let [post-ids  (grumpy/post-ids)
            first-ids (take (+ page-size (rem (count post-ids) page-size)) post-ids)]
        (grumpy/html-response (index-page first-ids))))
    grumpy/dev? (auth/wrap-session)))


(defn with-headers [handler headers]
  (fn [request]
    (some-> (handler request)
      (update :headers merge headers))))


(defn print-errors [handler]
  (fn [req]
    (try
      (handler req)
      (catch Exception e
        (.printStackTrace e)
        { :status 500
          :headers { "Content-type" "text/plain; charset=utf-8" }
          :body (with-out-str
                  (clojure.stacktrace/print-stack-trace (clojure.stacktrace/root-cause e))) }))))


(def app
  (compojure/routes
    (->
      routes
      (ring.middleware.params/wrap-params)
      (with-headers { "Cache-Control" "no-cache"
                      "Expires"       "-1" })
      (print-errors))
    (->
      (compojure.route/resources "/static" {:root "static"})
      (with-headers (if grumpy/dev?
                      { "Cache-Control" "no-cache"
                        "Expires"       "-1" }
                      { "Cache-Control" "max-age=315360000" })))
    (fn [req]
      { :status 404
        :body "404 Not found" })))


(defn -main [& args]
  (db/migrate! 2 db/update-1->2)
  (db/migrate! 3 db/update-2->3)
  (let [args-map (apply array-map args)
        port-str (or (get args-map "-p")
                     (get args-map "--port")
                     "8080")]
    (println "Starting web server on port" port-str)
    (web/run #'app { :port (Integer/parseInt port-str) })))


(comment
  (def server (-main "--port" "8080"))
  (web/stop server)
  (reset! auth/*tokens {}))
