(ns grumpy.auth
  (:require
    [rum.core :as rum]
    [clojure.set :as set]
    [clojure.string :as str]
    [clojure.java.io :as io]
    [clojure.java.shell :as shell]
    [io.pedestal.http.route :as route]
    [ring.middleware.session :as session]
    [io.pedestal.interceptor :as interceptor]
    [io.pedestal.http.body-params :as body-params]
    [ring.middleware.session.cookie :as session.cookie]
    [io.pedestal.http.ring-middlewares :as middlewares]
    [grumpy.core :as grumpy]
    [grumpy.routes :as routes])
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
  (println (str (java.time.Instant/now)) "[ Email sent ]\n  To:" to "\n  Subject:" subject "\n  Body:" body)
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


(def expire-session
  {:name ::expire-session
   :enter
   (fn [ctx]
     (let [created (-> ctx :request :session :created)]
       (if (and (some? created)
                (> (grumpy/age created) session-ttl-ms))
         (update ctx :request dissoc :session)
         ctx)))})


(def force-user
  {:name ::force-user
   :enter
   (fn [ctx]
     (if-some [u grumpy/forced-user]
       (assoc-in ctx [:request :session :user] u)
       ctx))
   :leave
   (fn [ctx]
     (if-some [u grumpy/forced-user]
       (update ctx :response assoc :cookies {"grumpy_user" {:value u}}
                                   :session {:user    u
                                             :created (grumpy/now)})
       ctx))})
    

(def session
  (middlewares/session
    {:store        (session.cookie/cookie-store {:key cookie-secret})
     :cookie-name  "grumpy_session"
     :cookie-attrs {:http-only true
                    :secure    (not grumpy/dev?)}}))


(def populate-session [session force-user expire-session])


(defn user [req]
  (get-in req [:session :user]))


(def require-user
  {:name ::require-user
   :enter (fn [{req :request :as ctx}]
            (if (nil? (user req))
              (assoc ctx :response (grumpy/redirect "/forbidden" {:redirect-url (:uri req)}))
              ctx))})


(rum/defc email-sent-page [message]
  (grumpy/page { :title "Something like that..."
                 :styles ["authors.css"] }
    [:.email-sent
      [:.email-sent_message message]]))


(rum/defc forbidden-page [redirect-url email]
  (grumpy/page {:title "Log in"
                :styles ["authors.css"]}
    [:form.forbidden
     {:action "/send-email"
      :method "post" }
     [:.form_row
      [:input {:type "text"
               :name "email"
               :placeholder "E-mail"
               :autofocus true
               :value email}]
      [:input {:type "hidden" :name "redirect-url" :value redirect-url}]]
     [:.form_row
      [:button "Send email"]]]))


(defn handle-forbidden [{:keys [query-params cookies]}]
  (let [user  (get-in cookies ["grumpy_user" :value])
        email (:email (grumpy/author-by :user user))]
    (grumpy/html-response (forbidden-page (:redirect-url query-params) email))))


(defn handle-send-email [{:keys [form-params] :as req}]
  (let [email (:email form-params)
        user  (:user (grumpy/author-by :email email))]
    (cond
      (nil? (grumpy/author-by :email email))
      (grumpy/redirect "/email-sent" {:message (str "You aren't the author, " email)})

      (some? (get-token email))
      (grumpy/redirect "/email-sent" {:message (str "Emailed link is still valid, " user)})
     
      :else
      (let [token        (gen-token)
            redirect-url (:redirect-url form-params)
            link         (grumpy/url (str grumpy/hostname "/authenticate")
                           {:email email
                            :token token
                            :redirect-url redirect-url})]
        (swap! *tokens assoc email { :value token :created (grumpy/now) })
        (send-email!
          {:to      email
           :subject (str "Log into Grumpy " (grumpy/format-date (grumpy/now)))
           :body    (str "<html><div style='text-align: center;'><a href=\"" link "\" style='display: inline-block; font-size: 16px; padding: 0.5em 1.75em; background: #c3c; color: white; text-decoration: none; border-radius: 4px;'>Login now!</a></div></html>")})
        (grumpy/redirect "/email-sent" {:message (str "Check your email, " user)})))))


(defn handle-email-sent [{:keys [query-params]}]
  (grumpy/html-response (email-sent-page (:message query-params))))


(defn handle-authenticate [{:keys [query-params]}] ;; ?email=...&token=...&redirect-url=...
  (let [email (:email query-params)
        user  (:user (grumpy/author-by :email email))
        redirect-url (if (str/blank? (:redirect-url query-params))
                       "/"
                       (:redirect-url query-params))]
    (if (= (:token query-params) (get-token email))
      (do
        (swap! *tokens dissoc email)
        (assoc (grumpy/redirect redirect-url)
          :cookies {"grumpy_user" {:value user}}
          :session {:user    user
                    :created (grumpy/now)}))
      {:status 403
       :body   "403 Bad token"})))


(def routes
  (routes/expand
    [:get  "/forbidden"    populate-session route/query-params        `handle-forbidden]
    [:post "/send-email"   populate-session (body-params/body-params) `handle-send-email]
    [:get  "/email-sent"   populate-session route/query-params        `handle-email-sent]
    [:get  "/authenticate" populate-session route/query-params        `handle-authenticate]
    [:get  "/logout"       populate-session (fn [_] (assoc (grumpy/redirect "/") :session nil))]))