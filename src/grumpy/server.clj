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
      (for [name (:pictures post)
            :let [src (str "/post/" (:id post) "/" name)]]
        (if (str/ends-with? name ".mp4")
          [:video.post_img { :autoplay true :loop true }
            [:source { :type "video/mp4" :src src }]]
          [:a { :href src :target :_blank }
            [:img.post_img { :src src }]]))
      [:.post_body
        { :dangerouslySetInnerHTML
          { :__html (grumpy/format-text
                      (str "<span class=\"post_author\">" (:author post) ": </span>" (:body post))) }}]
      [:p.post_meta
        (grumpy/format-date (:created post))
        " // " [:a {:href (str "/post/" (:id post))} "Ссылка"]
        [:a.post_meta_edit {:href (str "/post/" (:id post) "/edit")} "Править"]]]])


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


(defn sitemap [post-ids]
  (feed/sitemap post-ids))


(compojure/defroutes routes
  (compojure/GET "/" []
    (let [post-ids  (grumpy/post-ids)
          first-ids (take (+ page-size (rem (count post-ids) page-size)) post-ids)]
      (grumpy/html-response (index-page first-ids))))

  (compojure/GET "/post/:id/:img" [id img]
    (ring.util.response/file-response (str "grumpy_data/posts/" id "/" img)))

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
      :body (sitemap (grumpy/post-ids)) })

  (compojure/GET "/robots.txt" []
    { :status 200
      :headers { "Content-type" "text/plain" }
      :body (grumpy/resource "robots.txt") })

  (auth/wrap-session
    (compojure/routes
      #'auth/routes
      #'authors/routes)))


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
      (with-headers { "Cache-Control" "no-cache"
                      "Expires"       "-1" }))
    (fn [req]
      { :status 404
        :body "404 Not found" })))


(defn -main [& args]
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
