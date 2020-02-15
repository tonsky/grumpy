(ns grumpy.server
  (:require
   [rum.core :as rum]
   [clojure.stacktrace]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [io.pedestal.http :as http]
   [ring.util.response :as response]
   [clojure.stacktrace :as stacktrace]
   [io.pedestal.interceptor :as interceptor]
   [com.stuartsierra.component :as component]
   [io.pedestal.http.ring-middlewares :as middlewares]

   [grumpy.auth :as auth]
   [grumpy.feed :as feed]
   [grumpy.time :as time]
   [grumpy.core :as core]
   [grumpy.config :as config]
   [grumpy.routes :as routes]
   [grumpy.editor :as editor])
  (:import
   [java.io IOException]))


(def page-size 5)


(rum/defc post [post]
  [:.post
    { :data-id (:id post) }
    [:.post_side
      [:img.post_avatar
        { :src (if (some? (core/author-by :user (:author post)))
                 (str "/static/" (:author post) ".jpg")
                 "/static/guest.jpg")}]]
    [:.post_content
      (when-some [pic (:picture post)]
        (let [src (str "/post/" (:id post) "/" (:url pic))
              href (if-some [orig (:picture-original post)]
                     (str "/post/" (:id post) "/" (:url orig))
                     src)]
          (case (core/content-type pic)
            :content.type/video
              [:.post_video_outer
                [:video.post_video
                  { :autoplay true
                    :muted true
                    :loop true
                    :preload "auto"
                    :playsinline true
                    :onplay "toggle_video(this.parentNode, true);" }
                  [:source { :type (core/mime-type (:url pic)) :src src }]]
                [:.post_video_overlay.post_video_overlay-paused { :onclick "toggle_video(this.parentNode);"}]]
            :content.type/image
              (if-some [[w h] (:dimensions pic)]
                (let [[w' h'] (core/fit w h 550 500)]
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
          { :__html (core/format-text
                      (str "<span class=\"post_author\">" (:author post) ": </span>" (:body post))) }}]
      [:p.post_meta
        (time/format-date (:created post))
        " // " [:a {:href (str "/post/" (:id post))} "Hyperlink"]
        [:a.post_meta_edit {:href (str "/post/" (:id post) "/edit")} "Edit"]]]])


(rum/defc index-page [post-ids]
  (core/page {:page :index :scripts ["loader.js"]}
    (for [post-id post-ids]
      (post (core/get-post post-id)))))


(rum/defc posts-fragment [post-ids]
  (for [post-id post-ids]
    (post (core/get-post post-id))))


(rum/defc post-page [post-id]
  (core/page {:page :post}
    (post (core/get-post post-id))))


(def no-cache
  (interceptor/interceptor
    {:name  ::no-cache
     :leave (fn [ctx]
              (let [h (:headers (:response ctx))]
                (if (contains? h "Cache-Control")
                  ctx
                  (update ctx :response assoc :headers (assoc h "Cache-Control" "no-cache", "Expires" "-1")))))}))

(def routes
  (routes/expand
    [:get "/post/:post-id/:img"
     (fn [{{:keys [post-id img]} :path-params}]
       (response/file-response (str "grumpy_data/posts/" post-id "/" img)))]

    [:get "/post/:post-id"
     (fn [{{:keys [post-id]} :path-params}]
       (core/html-response (post-page post-id)))]

    [:get "/after/:post-id"
     (fn [{{:keys [post-id]} :path-params}]
       (when config/dev? (Thread/sleep 200))
       (if (and config/dev? (< (rand) 0.5))
         { :status 500 }
         (let [post-ids (->> (core/post-ids)
                             (drop-while #(not= % post-id))
                             (drop 1)
                             (take page-size))]
           {:status  200
            :headers { "Content-Type" "text/html; charset=utf-8" }
            :body    (rum/render-static-markup (posts-fragment post-ids))})))]

   [:get "/"
    (when config/dev? auth/populate-session)
    (fn [_]
      (let [post-ids  (core/post-ids)
            first-ids (take (+ page-size (rem (count post-ids) page-size)) post-ids)]
        (core/html-response (index-page first-ids))))]

   [:get "/static/*path" 
    (when-not config/dev?
      {:leave #(update-in % [:response :headers] assoc "Cache-Control" "max-age=315360000")})
    (fn [{{:keys [path]} :path-params}]
      (response/resource-response (str "static/" path)))]

   [:get "/feed.xml"
    (fn [_]
      {:status  200
       :headers { "Content-Type" "application/atom+xml; charset=utf-8" }
       :body    (feed/feed (take 10 (core/post-ids))) })]

   [:get "/sitemap.xml"
    (fn [_]
      {:status 200
       :headers { "Content-Type" "text/xml; charset=utf-8" }
       :body (feed/sitemap (core/post-ids))})]

   [:get "/robots.txt"
    (fn [_]
      {:status 200
       :headers {"Content-Type" "text/plain"}
       :body (core/resource "robots.txt")})]))


; Filtering out Broken pipe reporting
; io.pedestal.http.impl.servlet-interceptor/error-stylobate
(defn error-stylobate [{:keys [servlet-response] :as context} exception]
  (let [^Throwable cause (stacktrace/root-cause exception)]
    (cond
      (and (instance? IOException cause) (= "Broken pipe" (.getMessage cause)))
      :ignore ; (println "Ignoring java.io.IOException: Broken pipe")

      (and (instance? IOException cause) (= "Connection reset by peer" (.getMessage cause)))
      :ignore ; (println "Ignoring java.io.IOException: Connection reset by peer")

      :else
      (io.pedestal.log/error
        :msg "error-stylobate triggered"
        :exception exception
        :context context))
    (@#'io.pedestal.http.impl.servlet-interceptor/leave-stylobate context)))


; io.pedestal.http.impl.servlet-interceptor/stylobate
(def stylobate
  (io.pedestal.interceptor/interceptor {:name ::stylobate
                                        :enter @#'io.pedestal.http.impl.servlet-interceptor/enter-stylobate
                                        :leave @#'io.pedestal.http.impl.servlet-interceptor/leave-stylobate
                                        :error error-stylobate}))


(defrecord Server [opts crux server]
  component/Lifecycle
  (start [this]
    (core/log "[server] Starting web server at" (str (:host opts) ":" (:port opts)))
    (with-redefs [io.pedestal.http.impl.servlet-interceptor/stylobate stylobate]
      (let [server (-> {::http/routes (routes/sort (concat routes auth/routes editor/routes))
                        ::http/router :linear-search
                        ::http/type   :immutant
                        ::http/host   (:host opts)
                        ::http/port   (:port opts)
                        ::http/secure-headers {:content-security-policy-settings "object-src 'none'; script-src 'self' 'unsafe-inline' 'unsafe-eval'"}}
                     (http/default-interceptors)
                     (update ::http/interceptors conj no-cache)
                     (http/create-server)
                     (http/start))]
        (assoc this :server server))))
  (stop [this]
    (core/log "[server] Stopping web server")
    (http/stop server)
    (dissoc this :server)))


(def default-opts
  {:host "localhost"
   :port 8080})


(defn server
  ([] (server {}))
  ([opts]
   (let [opts' (merge-with #(or %2 %1) default-opts opts)]
     (map->Server {:opts opts'}))))