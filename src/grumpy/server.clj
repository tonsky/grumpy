(ns grumpy.server
  (:require
    [clojure.stacktrace :as stacktrace]
    [datascript.core :as d]
    [grumpy.auth :as auth]
    [grumpy.core.config :as config]
    [grumpy.core.fragments :as fragments]
    [grumpy.core.log :as log]
    [grumpy.core.mime :as mime]
    [grumpy.core.posts :as posts]
    [grumpy.core.routes :as routes]
    [grumpy.core.time :as time]
    [grumpy.core.web :as web]
    [grumpy.db :as db]
    [grumpy.feed :as feed]
    [grumpy.editor :as editor]
    [io.pedestal.interceptor :as interceptor]
    [io.pedestal.http :as http]
    [mount.core :as mount]
    [ring.util.response :as response]
    [rum.core :as rum])
  (:import
    [java.io IOException]))

(def page-size
  (or (config/get-optional ::page-size) 5))

(rum/defc post [post]
  [:.post
   {:data-id (:post/id post)}
   [:.post_side
    [:img.post_avatar {:src (fragments/avatar-url (:post/author post))}]]
   [:.post_content
    (when-some [media (:post/media post)]
      (let [src  (str "/media/" (:media/url media))
            href (if-some [full (:post/media-full post)]
                   (str "/media/" (:media/url full))
                   src)]
        (case (mime/type media)
          :mime.type/video
          [:.post_video_outer
           [:video.post_video
            {:autoplay true
             :muted true
             :loop true
             :preload "auto"
             :playsinline true
             :onplay "toggle_video(this.parentNode, true);" }
            [:source
             {:type (mime/mime-type (:media/url media))
              :src src}]]
           [:.controls
            [:button.paused {:onclick "toggle_video(this.parentNode.parentNode);"}]
            [:button.fullscreen {:onclick "toggle_video_fullscreen(this.parentNode.parentNode);"}]]]
          :mime.type/image
          (or
            (when-some [w (:media/width media)]
              (when-some [h (:media/height media)]
                (let [[w' h'] (fragments/fit w h 550 500)]
                  [:div {:style {:max-width w'}}
                   [:a.post_img.post_img-fix
                    { :href href
                     :target "_blank"
                     :style {:padding-bottom (-> (/ h w) (* 100) (double) (str "%"))}}
                    [:img {:src src}]]])))
            [:a.post_img.post_img-flex 
             {:href href
              :target "_blank"}
             [:img {:src src}]]))))
    [:.post_body
     {:dangerouslySetInnerHTML
      {:__html
       (fragments/format-text
         (str "<span class=\"post_author\">" (:post/author post) ": </span>" (:post/body post)))}}]
    [:p.post_meta
     (time/format-date (:post/created post))
     " // " [:a {:href (str "/post/" (:post/id post))} "Hyperlink"]
     [:a.post_meta_edit {:href (str "/post/" (:post/id post) "/edit")} "Edit"]]]])

(rum/defc index-page [post-ids]
  (web/page {:page :index :scripts ["loader.js"]}
    (let [db (db/db)]
      (map #(post (d/entity db [:post/id %])) post-ids))))

(rum/defc posts-fragment [post-ids]
  (let [db (db/db)]
    (map #(post (d/entity db [:post/id %])) post-ids)))

(rum/defc post-page [post-id]
  (web/page {:page :post}
    (post (d/entity (db/db) [:post/id post-id]))))

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
       (let [db  (db/db)
             url (->> (d/find-datom db :avet :media/old-url img)
                   :e (d/entity db) :media/url)]
         (web/moved-permanently (str "/media/" url))))]

    [:get "/media/*path"
     (fn [{{:keys [path]} :path-params}]
       (response/file-response (str "grumpy_data/" path)))]
    
    [:get "/post/:post-id"
     (fn [{{:keys [post-id]} :path-params}]
       (if (re-matches #"\d+" post-id)
         (web/html-response (post-page (parse-long post-id)))
         (let [id' (-> (db/db)
                     (d/entity [:post/old-id post-id])
                     :post/id)]
           (web/moved-permanently (str "/post/" id')))))]

    [:get "/after/:post-id"
     (fn [{{:keys [post-id]} :path-params}]
       (when config/dev? (Thread/sleep 200))
       (if (and config/dev? (< (rand) 0.5))
         {:status 500}
         (let [post-id  (parse-long post-id)
               post-ids (->> (posts/post-ids)
                          (drop-while #(not= % post-id))
                          (drop 1)
                          (take page-size))]
           {:status  200
            :headers {"Content-Type" "text/html; charset=utf-8"}
            :body    (rum/render-static-markup (posts-fragment post-ids))})))]

    [:get "/"
     (when config/dev? auth/populate-session)
     (fn [_]
       (let [post-ids  (posts/post-ids)
             first-ids (take (+ page-size (rem (count post-ids) page-size)) post-ids)]
         (web/html-response (index-page first-ids))))]

    [:get "/static/*path" 
     (when-not config/dev?
       {:leave #(update-in % [:response :headers] assoc "Cache-Control" "max-age=315360000")})
     (fn [{{:keys [path]} :path-params}]
       (response/resource-response (str "static/" path)))]

    [:get "/feed.xml"
     (fn [_]
       {:status  200
        :headers {"Content-Type" "application/atom+xml; charset=utf-8"}
        :body    (feed/feed (take 10 (posts/post-ids))) })]

    [:get "/sitemap.xml"
     (fn [_]
       {:status 200
        :headers {"Content-Type" "text/xml; charset=utf-8"}
        :body (feed/sitemap (posts/post-ids))})]

    [:get "/robots.txt"
     (fn [_]
       {:status 200
        :headers {"Content-Type" "text/plain"}
        :body (web/resource "robots.txt")})]))


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
  (io.pedestal.interceptor/interceptor
    {:name ::stylobate
     :enter @#'io.pedestal.http.impl.servlet-interceptor/enter-stylobate
     :leave @#'io.pedestal.http.impl.servlet-interceptor/leave-stylobate
     :error error-stylobate}))

(def *opts
  (atom
    {:host "localhost"
     :port 8080}))

(mount/defstate server
  :start
  (with-redefs [io.pedestal.http.impl.servlet-interceptor/stylobate stylobate]
    (let [{:keys [host port]} @*opts]
      (log/log "[server] Starting web server at" (str host ":" port))
      (-> {::http/routes (routes/sort (concat routes auth/routes editor/routes))
           ::http/router :linear-search
           ::http/type   :immutant
           ::http/host   host
           ::http/port   port
           ::http/secure-headers {:content-security-policy-settings "object-src 'none'; script-src 'self' 'unsafe-inline' 'unsafe-eval'"}}
        (http/default-interceptors)
        (update ::http/interceptors conj no-cache)
        (http/create-server)
        (http/start))))
  :stop
  (do
    (log/log "[server] Stopping web server")
    (http/stop server)))
