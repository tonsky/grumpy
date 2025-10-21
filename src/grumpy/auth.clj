(ns grumpy.auth
  (:require
   [clojure.string :as str]
   [grumpy.core.config :as config]
   [grumpy.core.files :as files]
   [grumpy.core.fragments :as fragments]
   [grumpy.core.posts :as posts]
   [grumpy.core.time :as time]
   [grumpy.core.url :as url]
   [grumpy.core.web :as web]
   [grumpy.telegram :as telegram]
   [ring.middleware.session :as session]
   [ring.middleware.session.cookie :as session.cookie]
   [rum.core :as rum])
  (:import
   [java.security SecureRandom]))

(defonce *tokens
  (atom {}))

(def token-ttl-ms
  (* 1000 60 15)) ;; 15 min

(def user-cookie-attrs
  {:path "/"
   :secure    (not config/dev?)
   :max-age   2147483647
   :same-site :lax})

(def session-cookie-attrs
  {:path      "/"
   :http-only true
   :secure    (not config/dev?)
   :max-age   2147483647
   :same-site :lax})

(defn- random-bytes [size]
  (let [seed (byte-array size)]
    (.nextBytes (SecureRandom.) seed)
    seed))

(def cookie-secret
  (config/get ::cookie-secret #(random-bytes 16)))

(def forced-user
  (config/get-optional ::forced-user))

(defn gen-token []
  (str
    (posts/encode (rand-int Integer/MAX_VALUE) 5)
    (posts/encode (rand-int Integer/MAX_VALUE) 5)))

(defn get-token [handle]
  (when-some [token (get @*tokens handle)]
    (let [created (:created token)]
      (when (<= (time/since created) token-ttl-ms)
        (:value token)))))

(defn wrap-force-user [handler]
  (fn [req]
    (if-some [u forced-user]
      (let [req' (assoc-in req [:session :user] u)
            resp (handler req')]
        (assoc resp
          :cookies {"grumpy_user" (assoc user-cookie-attrs :value u)}
          :session {:user    u
                    :created (time/now)}))
      (handler req))))

(defn wrap-session [handler]
  (session/wrap-session handler
    {:store        (session.cookie/cookie-store
                     {:key     cookie-secret
                      :readers files/readers})
     :cookie-name  "grumpy_session"
     :cookie-attrs session-cookie-attrs}))

(defn user [req]
  (get-in req [:session :user]))

(defn wrap-require-user [handler]
  (fn [req]
    (if (user req)
      (handler req)
      (web/redirect "/forbidden" {:redirect-url (:uri req)}))))

(rum/defc forbidden-page [redirect-url handle]
  (web/page {:title "Log in"
             :styles ["editor.css"]
             :subtitle? false}
    [:form.column.auth
     {:action "/send-link"
      :method "post" }
     [:div "E-mail or Telegram user:"]
     [:div
      [:.input
       [:input {:type "text"
                :name "handle"
                :autofocus true
                :value handle}]]
      [:input {:type "hidden" :name "redirect-url" :value redirect-url}]]
     [:div
      [:button.btn "Send authenticate link"]]]))

(defn handle-forbidden [{:keys [cookies]
                         {:strs [redirect-url]} :query-params}]
  (let [user   (get-in cookies ["grumpy_user" :value])
        author (fragments/author-by :user user)
        handle (:telegram/user author)]
    (web/html-response (forbidden-page redirect-url handle))))

(defn handle-send-link [{{:strs [handle redirect-url]} :form-params}]
  (let [handle    (-> handle str/trim str/lower-case)
        tg-author (fragments/author-by :telegram/user handle)
        user      (:user tg-author)]
    (cond
      (nil? user)
      (web/redirect "/link-sent" {:message (str "You are not the author, " handle)})

      (and (some? tg-author) (nil? (:telegram/user-chat tg-author)))
      (web/redirect "/link-sent" {:message (str "Please contact @nikitonsky to enable Telegram login, " handle)})

      (nil? (get-token handle))
      (let [token (gen-token)
            link  (url/build (str (config/get :grumpy.server/hostname) "/authenticate")
                    {:handle       handle
                     :token        token
                     :redirect-url redirect-url})]
        (swap! *tokens assoc handle {:value token :created (time/now)})
        (telegram/post! (:telegram/user-chat tg-author) "/sendMessage" {:text link :disable_web_page_preview true})
        (web/redirect "/link-sent" {:message (str "Check your Telegram, " user)}))

      (some? tg-author)
      (web/redirect "/link-sent" {:message (str "Link in Telegram is still valid, " user)}))))

(rum/defc link-sent-page [message]
  (web/page {:title "Machine says"
             :styles ["editor.css"]
             :subtitle? false}
    [:.link-sent
     [:.link-sent_message message]]))

(defn handle-link-sent [{{:strs [message]} :query-params}]
  (web/html-response (link-sent-page message)))

(defn handle-authenticate [{{:strs [handle redirect-url token]} :query-params}] ;; ?handle=...&token=...&redirect-url=...
  (let [author (fragments/author-by :telegram/user handle)
        user   (:user author)
        redirect-url (if (str/blank? redirect-url)
                       "/"
                       redirect-url)]
    (if (= token (get-token handle))
      (do
        (swap! *tokens dissoc handle)
        (assoc (web/redirect redirect-url)
          :cookies {"grumpy_user" (assoc user-cookie-attrs :value user)}
          :session {:user    user
                    :created (time/now)}))
      {:status 403
       :body   "403 Bad token"})))

(def apply-middlewares
  (comp
    wrap-force-user
    wrap-session))

(def routes
  {"GET /forbidden"    handle-forbidden
   "POST /send-link"   handle-send-link
   "GET /link-sent"    handle-link-sent
   "GET /authenticate" (apply-middlewares handle-authenticate)
   "GET /logout"       (apply-middlewares (fn [_] (assoc (web/redirect "/") :session nil)))})
