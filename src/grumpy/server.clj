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
    [grumpy.authors :as authors])
  (:gen-class))


(rum/defc post [post]
  [:.post
    [:.post_side
      [:img.post_avatar {:src (str "/static/" (:author post) ".jpg")}]]
    [:.post_body
      (for [name (:pictures post)]
        [:img.post_img { :src (str "/post/" (:id post) "/" name) }])
      (for [[p idx] (grumpy/zip (str/split (:body post) #"\n+") (range))]
        [:p.post_p
          (when (== 0 idx)
            [:span.post_author (:author post) ": "])
          p])
      [:p.post_meta
        (grumpy/render-date (:created post))
        " // " [:a {:href (str "/post/" (:id post))} "Ссылка"]
        [:span.post_meta_edit " × " [:a {:href (str "/post/" (:id post) "/edit")} "Править"]]]]])


(rum/defc index-page [post-ids]
  (grumpy/page {:index? true}
    (for [post-id post-ids]
      (post (grumpy/get-post post-id)))))


(rum/defc post-page [post-id]
  (grumpy/page {}
    (post (grumpy/get-post post-id))))


(compojure/defroutes routes
  (compojure/GET "/" []
    (grumpy/html-response (index-page (grumpy/post-ids))))

  (compojure/GET "/post/:id/:img" [id img]
    (ring.util.response/file-response (str "grumpy_data/posts/" id "/" img)))    

  (compojure/GET "/post/:post-id" [post-id]
    (grumpy/html-response (post-page post-id)))

  (auth/wrap-session
    (compojure/routes
      auth/routes
      authors/routes)))


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
