(ns grumpy.auth
  (:require
    [rum.core :as rum]
    [clojure.set :as set]
    [grumpy.core :as grumpy]
    [clojure.java.io :as io]
    [compojure.core :as compojure]
    [clojure.java.shell :as shell]
    [ring.middleware.session :as session]
    [ring.middleware.session.cookie :as session.cookie])
  (:import
    [java.security SecureRandom]))


(defonce *tokens (atom {}))
(def session-ttl-ms (* 1000 86400 14)) ;; 14 days
(def token-ttl-ms (* 1000 60 15)) ;; 15 min


(defn random-bytes [size]
  (let [seed (byte-array size)]
    (.nextBytes (SecureRandom.) seed)
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
  (println "[ Email sent ]\n  To:" to "\n  Subject:" subject "\n  Body:" body)
  (shell/sh
    "mail"
    "-s"
    subject
    to
    "-a" "Content-Type: text/html"
    "-a" "From: Grumpy Admin <admin@grumpy.website>"
    :in body))


(defn gen-token []
  (str
    (grumpy/encode (rand-int Integer/MAX_VALUE) 5)
    (grumpy/encode (rand-int Integer/MAX_VALUE) 5)))


(defn get-token [email]
  (when-some [token (get @*tokens email)]
    (let [created (:created token)]
      (when (<= (grumpy/age created) token-ttl-ms)
        (:value token)))))


(defn- expire-session [handler]
  (fn [req]
    (let [created (:created (:session req))]
      (if (and (some? created)
               (> (grumpy/age created) session-ttl-ms))
        (handler (dissoc req :session))
        (handler req)))))


(defn force-user [handler]
  (fn [req]
    (if-some [u grumpy/forced-user]
      (some-> req
        (assoc-in [:session :user] u)
        (handler)
        (assoc :cookies { "grumpy_user" { :value u }}
               :session { :user    u
                          :created (grumpy/now) }))
      (handler req))))
    

(defn wrap-session [handler]
  (-> handler
    (expire-session)
    (force-user)
    (session/wrap-session
      { :store        (session.cookie/cookie-store { :key cookie-secret })
        :cookie-name  "grumpy_session"
        :cookie-attrs { :http-only true
                        :secure    (not grumpy/dev?) }})))


(defn user [req]
  (get-in req [:session :user]))


(defn check-session [req]
  (when (nil? (user req))
    (grumpy/redirect "/forbidden" { :redirect-url (:uri req) })))


(rum/defc email-sent-page [message]
  (grumpy/page { :title "Something like that..."
                 :styles ["authors.css"] }
    [:.email-sent
      [:.email-sent_message message]]))


(rum/defc forbidden-page [redirect-url email]
  (grumpy/page { :title "Log in"
                 :styles ["authors.css"] }
    [:form.forbidden
      { :action "/send-email"
             :method "post" }
      [:.form_row
        [:input { :type "text"
                  :name "email"
                  :placeholder "E-mail"
                  :autofocus true
                  :value email }]
        [:input { :type "hidden" :name "redirect-url" :value redirect-url }]]
      [:.form_row
        [:button "Send email"]]]))


(compojure/defroutes routes
  (compojure/GET "/forbidden" [:as req]
    (let [redirect-url (get (:params req) "redirect-url")
          user         (get-in (:cookies req) ["grumpy_user" :value])
          email        (:email (grumpy/author-by :user user))]
      (grumpy/html-response (forbidden-page redirect-url email))))

  (compojure/GET "/authenticate" [:as req] ;; ?email=...&token=...&redirect-url=...
    (let [email        (get (:params req) "email")
          user         (:user (grumpy/author-by :email email))
          token        (get (:params req) "token")
          redirect-url (get (:params req) "redirect-url")]
      (if (= token (get-token email))
        (do
          (swap! *tokens dissoc email)
          (assoc
            (grumpy/redirect redirect-url)
            :cookies { "grumpy_user" { :value user }}
            :session { :user    user
                       :created (grumpy/now) }))
        { :status 403
          :body   "403 Bad token" })))

  (compojure/GET "/logout" [:as req]
    (assoc
      (grumpy/redirect "/")
      :session nil))

  (compojure/POST "/send-email" [:as req]
    (let [params (:params req)
          email  (get params "email")
          user   (:user (grumpy/author-by :email email))]
      (cond
        (nil? (grumpy/author-by :email email))
          (grumpy/redirect "/email-sent" { :message (str "You aren't the author, " email) })
        (some? (get-token email))
          (grumpy/redirect "/email-sent" { :message (str "Emailed link is still valid, " user) })
        :else
          (let [token        (gen-token)
                redirect-url (get params "redirect-url")
                link         (grumpy/url (str grumpy/hostname "/authenticate")
                               { :email email
                                 :token token
                                 :redirect-url redirect-url })]
            (swap! *tokens assoc email { :value token :created (grumpy/now) })
            (send-email!
              { :to      email
                :subject (str "Log into Grumpy " (grumpy/format-date (grumpy/now)))
                :body    (str "<html><div style='text-align: center;'><a href=\"" link "\" style='display: inline-block; font-size: 16px; padding: 0.5em 1.75em; background: #c3c; color: white; text-decoration: none; border-radius: 4px;'>Login now!</a></div></html>") })
            (grumpy/redirect "/email-sent" { :message (str "Check your email, " user) })))))

  (compojure/GET "/email-sent" [:as req]
    (grumpy/html-response (email-sent-page (get-in req [:params "message"])))))
