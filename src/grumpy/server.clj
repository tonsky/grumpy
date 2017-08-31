(ns grumpy.server
  (:require
    [compojure.route]
    [rum.core :as rum]
    [ring.util.response]
    [clojure.edn :as edn]
    [immutant.web :as web]
    [compojure.core :as compojure]
    [clojure.java.io :as io])
  (:import
    [org.joda.time DateTime]
    [org.joda.time.format DateTimeFormat])
  (:gen-class))


(def styles (slurp (io/resource "style.css")))
(def script (slurp (io/resource "script.js")))
(def date-formatter (DateTimeFormat/forPattern "dd.MM.YYYY"))


(defn render-date [inst]
  (.print date-formatter (DateTime. inst)))


(rum/defc post [post]
  [:.post
    [:.post_sidebar
      [:img.avatar {:src (str "/i/" (:author post) ".jpg")}]]
    [:div
      (for [name (:pictures post)]
        [:img { :src (str "/post/" (:id post) "/" name) }])
      [:p [:span.author (:author post) ": "] (:body post)]
      [:p.meta (render-date (:created post)) " // " [:a {:href (str "/post/" (:id post))} "Ссылка"]]]])


(rum/defc page [title & children]
  [:html
    [:head
      [:meta { :http-equiv "Content-Type" :content "text/html; charset=UTF-8"}]
      [:title title]
      [:meta { :name "viewport" :content "width=device-width, initial-scale=1.0"}]
      [:style {:dangerouslySetInnerHTML { :__html styles}}]]
    [:body
      [:header
        [:h1 "Ворчание ягнят"]
        [:p#site_subtitle "Это не текст, это ссылка. Не нажимайте на ссылку."]]
      children
    [:footer
      [:a { :href "https://twitter.com/nikitonsky" } "Никита Прокопов"]
      ", "
      [:a { :href "https://twitter.com/freetonik" } "Рахим Давлеткалиев"]
      ". 2017. All fights retarded."
      [:br]
      [:a { :href "/feed" :rel "alternate" :type "application/rss+xml" } "RSS"]]    
    
    [:script {:dangerouslySetInnerHTML { :__html script}}]]])


(rum/defc index [post-ids]
  (page "Ворчание ягнят"
    (for [post-id post-ids
          :let [path (str "posts/" post-id "/post.edn")
                p    (-> (io/file path)
                         (slurp)
                         (edn/read-string))]]
      (post p))))


(defn render-html [component]
  (str "<!DOCTYPE html>\n" (rum/render-static-markup component)))


(defn post-ids []
  (for [name (seq (.list (io/file "posts")))
        :let [child (io/file "posts" name)]
        :when (.isDirectory child)]
    name))

; "/"
; "/feed"
; "/post/:id"
; /post/:id/pict.jpg
; "/login"
; GET "/write"
; POST "/write"


(compojure/defroutes routes
  (compojure.route/resources "/i" {:root "public/i"})

  (compojure/GET "/" []
    { :body (render-html (index (post-ids))) })

  (compojure/GET "/post/:id/:img" [id img]
    (ring.util.response/file-response (str "posts/" id "/" img)))

  ; (compojure/GET "/write" []
  ;   { :body "WRITE" })

  ; (compojure/POST "/write" [:as req]
  ;   { :body "POST" })
    )


(defn with-headers [handler headers]
  (fn [request]
    (some-> (handler request)
      (update :headers merge headers))))
    

(def app
  (-> routes
    (with-headers { "Content-Type"  "text/html; charset=utf-8"
                    "Cache-Control" "no-cache"
                    "Expires"       "-1" })))


(defn -main [& args]
  (let [args-map (apply array-map args)
        port-str (or (get args-map "-p")
                     (get args-map "--port")
                     "8080")]
    (println "Starting web server on port" port-str)
    (web/run #'app { :port (Integer/parseInt port-str) })))


(comment
  (def server (-main "--port" "8080"))
  (web/stop server))

