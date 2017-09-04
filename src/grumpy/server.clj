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
  (:import
    [java.util Date])
  (:gen-class))


(rum/defc post [post]
  [:.post
    [:.post_side
      [:img.post_avatar {:src (str "/static/" (:author post) ".jpg")}]]
    [:.post_body
      (for [name (:pictures post)]
        [:img.post_img { :src (str "/post/" (:id post) "/" name) }])
      (for [[p idx] (grumpy/zip (str/split (:body post) #"(\r?\n)+") (range))]
        [:p.post_p
          (when (== 0 idx)
            [:span.post_author (:author post) ": "])
          p])
      [:p.post_meta
        (grumpy/format-date (:created post))
        " // " [:a {:href (str "/post/" (:id post))} "Ссылка"]
        [:span.post_meta_edit " × " [:a {:href (str "/post/" (:id post) "/edit")} "Править"]]]]])


(rum/defc index-page [post-ids]
  (grumpy/page {:index? true}
    (for [post-id post-ids]
      (post (grumpy/get-post post-id)))))


(rum/defc post-page [post-id]
  (grumpy/page {}
    (post (grumpy/get-post post-id))))


(defn feed [post-ids]
  (let [posts   (map grumpy/get-post post-ids)
        updated (->> posts
                     (map :updated)
                     (map #(.getTime ^Date %))
                     (reduce max)
                     (Date.))]
    (str
      "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
      "<feed xmlns=\"http://www.w3.org/2005/Atom\" xml:lang=\"en-US\">\n"
      "  <title>Ворчание ягнят</title>\n"
      "  <link type=\"application/atom+xml\" href=\"" grumpy/hostname "/feed.xml\" rel=\"self\" />\n"
      "  <link rel=\"alternate\" type=\"text/html\" href=\"" grumpy/hostname "/\" />\n"
      "  <id>" grumpy/hostname "/</id>\n"
      "  <updated>" (grumpy/format-iso-inst updated) "</updated>\n"
      (str/join ""
        (for [author grumpy/authors]
          (str "  <author><name>" (:name author) "</name><email>" (:email author) "</email></author>\n")))
      (str/join ""
        (for [post posts
              :let [author (grumpy/author-by :user (:author post))]]
          (str 
            "\n  <entry>\n"
            "    <title>Ворчание ягнят</title>\n"
            "    <link rel=\"alternate\" type=\"text/html\" href=\"" grumpy/hostname "/post/" (:id post) "\" />\n"
            "    <id>" grumpy/hostname "/post/" (:id post) "</id>\n"
            "    <published>" (grumpy/format-iso-inst (:created post)) "</published>\n"
            "    <updated>" (grumpy/format-iso-inst (:updated post)) "</updated>\n"
            "    <content type=\"html\"><![CDATA[\n"
            (str/join ""
              (for [name (:pictures post)]
                (str "      <p><img src=\"" grumpy/hostname "/post/" (:id post) "/" name "\"></p>\n")))
            (str/join ""
              (for [[paragraph idx] (grumpy/zip (str/split (:body post) #"(\r?\n)+") (range))]
                (str
                  "      <p>"
                  (when (== 0 idx)
                    (str "<strong>" (:author post) ": </strong>"))
                  paragraph
                  "</p>\n")))
            "    ]]></content>\n"
            "    <author><name>" (:name author) "</name><email>" (:email author) "</email></author>\n"
            "  </entry>\n"
          )))
        "\n</feed>"
      )))


(compojure/defroutes routes
  (compojure/GET "/" []
    (grumpy/html-response (index-page (grumpy/post-ids))))

  (compojure/GET "/post/:id/:img" [id img]
    (ring.util.response/file-response (str "grumpy_data/posts/" id "/" img)))    

  (compojure/GET "/post/:post-id" [post-id]
    (grumpy/html-response (post-page post-id)))

  (compojure/GET "/feed.xml" []
    { :status 200
      :headers { "Content-type" "application/atom+xml; charset=utf-8" }
      :body (feed (take 10 (grumpy/post-ids))) })

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
