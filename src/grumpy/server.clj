(ns grumpy.server
  (:require
    [compojure.route]
    [rum.core :as rum]
    [clojure.stacktrace]
    [ring.util.response]
    [clojure.edn :as edn]
    [immutant.web :as web]
    [clojure.string :as str]
    [clojure.java.io :as io]
    [ring.middleware.params]
    [compojure.core :as compojure]
    [clojure.java.shell :as shell]
    [ring.middleware.multipart-params]
    [ring.middleware.session :as session]
    [ring.middleware.session.cookie :as session.cookie])
  (:import
    [java.util UUID Date]
    [org.joda.time DateTime]
    [org.joda.time.format DateTimeFormat DateTimeFormatter])
  (:gen-class))


(.mkdirs (io/file "grumpy_data"))


(defmacro from-config [name default-value]
 `(let [file# (io/file "grumpy_data" ~name)]
    (when-not (.exists file#)
      (spit file# ~default-value))
    (slurp file#)))


(def styles (slurp (io/resource "style.css")))
(def script (slurp (io/resource "script.js")))
(def date-formatter (DateTimeFormat/forPattern "dd.MM.YYYY"))


(def authors (edn/read-string
               (from-config "AUTHORS" 
                 (pr-str { "prokopov@gmail.com" "nikitonsky"
                           "freetonik@gmail.com" "freetonik" }))))


(def hostname (from-config "HOSTNAME" "http://grumpy.website"))


(defonce *tokens (atom {}))
(def session-ttl (* 1000 86400 14)) ;; 14 days
(def token-ttl-ms (* 1000 60 15)) ;; 15 min


(defn zip [coll1 coll2]
  (map vector coll1 coll2))


(defn now ^Date []
  (Date.))


(defn render-date [^Date inst]
  (.print ^DateTimeFormatter date-formatter (DateTime. inst)))


(defn encode-uri-component [s]
  (-> s
      (java.net.URLEncoder/encode "UTF-8")
      (str/replace #"\+"   "%20")
      (str/replace #"\%21" "!")
      (str/replace #"\%27" "'")
      (str/replace #"\%28" "(")
      (str/replace #"\%29" ")")
      (str/replace #"\%7E" "~")))


(defn redirect
  ([url]
    { :status 302
      :headers { "Location" url } })
  ([url query]
    (let [query-str (map
                      (fn [[k v]]
                        (str (name k) "=" (encode-uri-component v)))
                      query)]
      { :status 302
        :headers { "Location" (str url "?" (str/join "&" query-str)) }})))


(defn random-bytes [size]
  (let [seed (byte-array size)]
    (.nextBytes (java.security.SecureRandom.) seed)
    seed))


(defn save-bytes! [file ^bytes bytes]
  (with-open [os (io/output-stream (io/file file))]
    (.write os bytes)))


(defn read-bytes [file len]
  (with-open [is (io/input-stream (io/file file))]
    (let [res (make-array Byte/TYPE len)]
      (.read is res 0 len)
      res)))


(when-not (.exists (io/file "grumpy_data/COOKIE_SECRET"))
  (save-bytes! "grumpy_data/COOKIE_SECRET" (random-bytes 16)))


(def cookie-secret (read-bytes "grumpy_data/COOKIE_SECRET" 16))


(defn send-email! [{:keys [to subject body]}]
  (println "[ Email sent ]\nTo:" to "\nSubject:" subject "\nBody:" body)
  (shell/sh
    "mail"
    "-s"
    subject
    to
    "-a" "Content-Type: text/html"
    "-a" "From: Grumpy Admin <admin@grumpy.website>"
    :in body))


(rum/defc post [post]
  [:.post
    [:.post_sidebar
      [:img.avatar {:src (str "/i/" (:author post) ".jpg")}]]
    [:div
      (for [name (:pictures post)]
        [:img { :src (str "/post/" (:id post) "/" name) }])
      (for [[p idx] (zip (str/split (:body post) #"\n+") (range))]
        [:p
          (when (== 0 idx)
            [:span.author (:author post) ": "])
          p])
      [:p.meta (render-date (:created post)) " // " [:a {:href (str "/post/" (:id post))} "Ссылка"]]]])


(rum/defc page [opts & children]
  (let [{:keys [title index?]
         :or {title  "Ворчание ягнят"
              index? false}} opts]
    [:html
      [:head
        [:meta { :http-equiv "Content-Type" :content "text/html; charset=UTF-8"}]
        [:title title]
        [:meta { :name "viewport" :content "width=device-width, initial-scale=1.0"}]
        [:style {:dangerouslySetInnerHTML { :__html styles}}]]
      [:body
        [:header
          (if index?
            [:h1 title]
            [:h1 [:a {:href "/"} title]])
          [:p#site_subtitle "Это не текст, это ссылка. Не нажимайте на ссылку."]]
        children
      [:footer
        [:a { :href "https://twitter.com/nikitonsky" } "Никита Прокопов"]
        ", "
        [:a { :href "https://twitter.com/freetonik" } "Рахим Давлеткалиев"]
        ". 2017. All fights retarded."
        [:br]
        [:a { :href "/feed" :rel "alternate" :type "application/rss+xml" } "RSS"]]    
      
      [:script {:dangerouslySetInnerHTML { :__html script}}]]]))


(defn safe-slurp [source]
  (try
    (slurp source)
    (catch Exception e
      nil)))
      

(defn get-post [post-id]
  (let [path (str "grumpy_data/posts/" post-id "/post.edn")]
    (some-> (io/file path)
            (safe-slurp)
            (edn/read-string))))


(def ^:const encode-table "-0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz")


(defn encode [num len]
  (loop [num  num
         list ()
         len  len]
    (if (== 0 len)
      (str/join list)
      (recur (bit-shift-right num 6)
             (let [ch (nth encode-table (bit-and num 0x3F))]
              (conj list ch))
             (dec len)))))


(defn next-post-id []
  (str
    (encode (quot (System/currentTimeMillis) 1000) 6)
    (encode (rand-int (* 64 64 64)) 3)))


(defn gen-token []
  (str
    (encode (rand-int Integer/MAX_VALUE) 5)
    (encode (rand-int Integer/MAX_VALUE) 5)))


(defn since [^Date inst]
  (- (.getTime (now)) (.getTime inst)))


(defn get-token [email]
  (when-some [token (get @*tokens email)]
    (let [created (:created token)]
      (when (<= (since created) token-ttl-ms)
        (:value token)))))


(defn save-post! [post pictures]
  (let [dir           (io/file (str "grumpy_data/posts/" (:id post)))
        picture-names (for [[picture idx] (zip pictures (range))
                            :let [in-name  (:filename picture)
                                  [_ ext]  (re-matches #".*(\.[^\.]+)" in-name)]]
                        (str (:id post) "_" (inc idx) ext))]
    (.mkdirs dir)
    (doseq [[picture name] (zip pictures picture-names)]
      (io/copy (:tempfile picture) (io/file dir name))
      (.delete (:tempfile picture)))
    (spit (io/file dir "post.edn") (pr-str (assoc post :pictures (vec picture-names))))))


(rum/defc index-page [post-ids]
  (page {:index? true}
    (for [post-id post-ids]
      (post (get-post post-id)))))


(rum/defc post-page [post-id]
  (page {}
    (post (get-post post-id))))


(rum/defc edit-post-page [post-id]
  (let [post    (get-post post-id)
        create? (nil? post)]
    (page {:title (if create? "Создание" "Редактирование")}
      [:form { :action (str "/post/" post-id "/edit")
               :enctype "multipart/form-data"
               :method "post" }
        [:.edit_post_picture
          [:input { :type "file" :name "picture"}]]
        [:.edit_post_body
          [:textarea
            { :value (:body post "")
              :name "body"
              :placeholder "Пиши сюда..."
              :autofocus true }]]
        [:.edit_post_submit
          [:button.btn (if create? "Создать" "Сохранить")]]])))


(rum/defc email-sent-page [message]
  (page {}
    [:div.email_sent_message message]))


(rum/defc forbidden-page [redirect-url]
  (page { :title "Вход" }
    [:form { :action "/send-email"
             :method "post" }
      [:div.forbidden_email
        [:input { :type "text" :name "email" :placeholder "E-mail" :autofocus true }]]
      [:div
        [:input { :type "hidden" :name "redirect-url" :value redirect-url }]]
      [:div
        [:button.btn "Отправить письмецо"]]]))


(defn render-html [component]
  (str "<!DOCTYPE html>\n" (rum/render-static-markup component)))


(defn post-ids []
  (->>
    (for [name (seq (.list (io/file "grumpy_data/posts")))
          :let [child (io/file "grumpy_data/posts" name)]
          :when (.isDirectory child)]
      name)
    (sort)
    (reverse)))


(defn check-session [req]
  (when (nil? (get-in req [:session :user]))
    (redirect "/forbidden" { :redirect-url (:uri req) })))


(compojure/defroutes routes
  (compojure.route/resources "/i" {:root "public/i"})

  (compojure/GET "/" []
    { :body (render-html (index-page (post-ids))) })

  (compojure/GET "/post/:id/:img" [id img]
    (ring.util.response/file-response (str "grumpy_data/posts/" id "/" img)))    

  (compojure/GET "/post/:post-id" [post-id]
    { :body (render-html (post-page post-id)) })

  (compojure/GET "/forbidden" [:as req]
    { :body (render-html (forbidden-page (get (:params req) "redirect-url"))) })

  (compojure/GET "/authenticate" [:as req] ;; ?email=...&token=...&redirect-url=...
    (let [email        (get (:params req) "email")
          user         (get authors email)
          token        (get (:params req) "token")
          redirect-url (get (:params req) "redirect-url")]
      (if (= token (get-token email))
        (do
          (swap! *tokens dissoc email)
          (assoc
            (redirect redirect-url)
            :session { :user    user
                      :created (now) }))
        { :status 403
          :body   "403 Bad token" })))

  (compojure/GET "/logout" [:as req]
    (assoc
      (redirect "/")
      :session nil))

  (compojure/POST "/send-email" [:as req]
    (let [params (:params req)
          email  (get params "email")]
      (cond
        (not (contains? authors email))
          (redirect "/email-sent" { :message (str "Ты не автор, " email) })
        (some? (get-token email))
          (redirect "/email-sent" { :message (str "Token still alive, check your email, " email) })
        :else
          (let [token        (gen-token)
                redirect-url (get params "redirect-url")
                link         (str hostname
                                  "/authenticate"
                                  "?email=" (encode-uri-component email)
                                  "&token=" (encode-uri-component token)
                                  "&redirect-url=" (encode-uri-component redirect-url))]
            (swap! *tokens assoc email { :value token :created (now) })
            (send-email!
              { :to      email
                :subject (str "Вход в Grumpy " (render-date (now)))
                :body    (str "<html><div style='text-align: center;'><a href='" link "' style='display: inline-block; font-size: 16px; padding: 0.5em 1.75em; background: #c3c; color: white; text-decoration: none; border-radius: 4px;'>Войти в сайтик!</a></div></html>") })
            (redirect "/email-sent" { :message (str "Check your mail, " email) })))))

  (compojure/GET "/email-sent" [:as req]
    { :body (render-html (email-sent-page (get-in req [:params "message"]))) })

  (compojure/GET "/new" [:as req]
    (or
      (check-session req)
      (redirect (str "/post/" (next-post-id) "/edit"))))

  (compojure/GET "/post/:post-id/edit" [post-id :as req]
    (or
      (check-session req)
      { :body (render-html (edit-post-page post-id)) }))

  (ring.middleware.multipart-params/wrap-multipart-params
    (compojure/POST "/post/:post-id/edit" [post-id :as req]
      (or
        (check-session req)
        (let [params  (:multipart-params req)
              body    (get params "body")
              picture (get params "picture")]
          (save-post! { :id      post-id
                        :body    body
                        :author  (get-in req [:session :user])
                        :created (now) }
                      [picture])
          (redirect "/")))))

  (fn [req]
    { :status 404
      :body "404 Not found" }))


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


(defn expire-session [handler]
  (fn [req]
    (let [created (:created (:session req))]
      (if (and (some? created)
               (> (since created) session-ttl))
        (handler (dissoc req :session))
        (handler req)))))


(def app
  (-> 
    routes
    (expire-session)
    (session/wrap-session
      { :store        (session.cookie/cookie-store { :key cookie-secret })
        :cookie-name  "grumpy"
        :cookie-attrs { :http-only true
                        :secure    false ;; FIXME
                      } })
    (ring.middleware.params/wrap-params)
    (with-headers { "Content-Type"  "text/html; charset=utf-8"
                    "Cache-Control" "no-cache"
                    "Expires"       "-1" })
    (print-errors)))


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
  (reset! *tokens {}))
