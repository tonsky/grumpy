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
    [grumpy.routes :as routes]
    [grumpy.macros :refer [cond+]]
    [grumpy.telegram :as telegram])
  (:import
    [java.security SecureRandom]))


(defonce *tokens (atom {}))
(def session-ttl-ms (* 1000 86400 14)) ;; 14 days
(def token-ttl-ms (* 1000 60 15)) ;; 15 min


(def user-cookie-attrs
  {:path "/"
   :secure    (not grumpy/dev?)
   :max-age   2147483647
   :same-site :lax})


(def session-cookie-attrs
  {:path      "/"
   :http-only true
   :secure    (not grumpy/dev?)
   :max-age   (quot session-ttl-ms 1000)
   :same-site :lax})


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
  (grumpy/sh
    "mail"
    "-s"
    subject
    to
    "-a" "Content-Type: text/html"
    "-a" "From: Grumpy Admin <admin@grumpy.website>"
    :in body))


(defn send-link! [email link]
  (send-email!
    {:to      email
     :subject (str "Log into Grumpy " (grumpy/format-date (grumpy/now)))
     :body    (str "<html><div style='text-align: center;'><a href=\"" link "\" style='display: inline-block; font-size: 16px; padding: 0.5em 1.75em; background: #c3c; color: white; text-decoration: none; border-radius: 4px;'>Login now!</a></div></html>")}))


(defn gen-token []
  (str
    (grumpy/encode (rand-int Integer/MAX_VALUE) 5)
    (grumpy/encode (rand-int Integer/MAX_VALUE) 5)))


(defn get-token [handle]
  (when-some [token (get @*tokens handle)]
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
       (update ctx :response assoc :cookies {"grumpy_user" (assoc user-cookie-attrs :value u)}
                                   :session {:user    u
                                             :created (grumpy/now)})
       ctx))})

(def session
  (middlewares/session
    {:store        (session.cookie/cookie-store {:key cookie-secret})
     :cookie-name  "grumpy_session"
     :cookie-attrs session-cookie-attrs}))


(def populate-session [session force-user expire-session])


(defn user [req]
  (get-in req [:session :user]))


(def require-user
  {:name ::require-user
   :enter (fn [{req :request :as ctx}]
            (if (nil? (user req))
              (assoc ctx :response (grumpy/redirect "/forbidden" {:redirect-url (:uri req)}))
              ctx))})


(rum/defc forbidden-page [redirect-url handle]
  (grumpy/page {:title "Log in"
                :styles ["authors.css"]
                :subtitle? false}
    [:form.forbidden
     {:action "/send-link"
      :method "post" }
     [:.form_row "E-mail or Telegram user:"]
     [:.form-row
      [:input {:type "text"
               :name "handle"
               :autofocus true
               :value handle}]
      [:input {:type "hidden" :name "redirect-url" :value redirect-url}]]
     [:.form_row
      [:button "Send authenticate link"]]]))


(defn handle-forbidden [{:keys [query-params cookies]}]
  (let [user   (get-in cookies ["grumpy_user" :value])
        author (grumpy/author-by :user user)
        handle (or (and (:telegram/user-chat author) (:telegram/user author))
                 (:email author))]
    (grumpy/html-response (forbidden-page (:redirect-url query-params) handle))))


(defn handle-send-link [{:keys [form-params] :as req}]
  (let [handle       (-> (:handle form-params) str/trim str/lower-case)
        email-author (grumpy/author-by :email handle)
        tg-author    (grumpy/author-by :telegram/user handle)
        user         (:user (or email-author tg-author))]
    (cond
      (nil? user)
      (grumpy/redirect "/link-sent" {:message (str "You are not the author, " handle)})

      (and (some? tg-author) (nil? (:telegram/user-chat tg-author)))
      (grumpy/redirect "/link-sent" {:message (str "Please contact @nikitonsky to enable Telegram login, " handle)})

      (nil? (get-token handle))
      (let [token        (gen-token)
            redirect-url (:redirect-url form-params)
            link         (grumpy/url (str grumpy/hostname "/authenticate")
                           {:handle       handle
                            :token        token
                            :redirect-url redirect-url})]
        (swap! *tokens assoc handle {:value token :created (grumpy/now)})
        (if email-author
          (send-link! handle link)
          (telegram/post! (:telegram/user-chat tg-author) "/sendMessage" {:text link :disable_web_page_preview true}))
        (grumpy/redirect "/link-sent" {:message (str "Check your " (if email-author "email" "Telegram") ", " user)}))

      (some? email-author)
      (grumpy/redirect "/link-sent" {:message (str "Emailed link is still valid, " user)})

      (some? tg-author)
      (grumpy/redirect "/link-sent" {:message (str "Link in Telegram is still valid, " user)}))))


(rum/defc link-sent-page [message]
  (grumpy/page {:title "Machine says"
                :styles ["authors.css"]
                :subtitle? false}
    [:.link-sent
      [:.link-sent_message message]]))


(defn handle-link-sent [{:keys [query-params]}]
  (grumpy/html-response (link-sent-page (:message query-params))))


(defn handle-authenticate [{:keys [query-params]}] ;; ?handle=...&token=...&redirect-url=...
  (let [handle (:handle query-params)
        author (or (grumpy/author-by :email handle)
                 (grumpy/author-by :telegram/user handle))
        user   (:user author)
        redirect-url (if (str/blank? (:redirect-url query-params))
                       "/"
                       (:redirect-url query-params))]
    (if (= (:token query-params) (get-token handle))
      (do
        (swap! *tokens dissoc handle)
        (assoc (grumpy/redirect redirect-url)
          :cookies {"grumpy_user" (assoc user-cookie-attrs :value user)}
          :session {:user    user
                    :created (grumpy/now)}))
      {:status 403
       :body   "403 Bad token"})))


(def routes
  (routes/expand
    [:get  "/forbidden"    populate-session route/query-params        `handle-forbidden]
    [:post "/send-link"    populate-session (body-params/body-params) `handle-send-link]
    [:get  "/link-sent"    populate-session route/query-params        `handle-link-sent]
    [:get  "/authenticate" populate-session route/query-params        `handle-authenticate]
    [:get  "/logout"       populate-session (fn [_] (assoc (grumpy/redirect "/") :session nil))]))