(ns grumpy.telegram
  (:require
    [clj-http.client :as http]
    [clojure.string :as str]
    [datascript.core :as d]
    [grumpy.core.coll :as coll]
    [grumpy.core.config :as config]
    [grumpy.core.fragments :as fragments]
    [grumpy.core.log :as log]
    [grumpy.core.mime :as mime]
    [grumpy.db :as db]))


(def ^:dynamic token
  (config/get-optional ::token))


(def ^:dynamic channel
  (or (config/get-optional ::channel)
    "grumpy_test"))


(defn post!
  ([url params]
   (post! channel url params))
  ([channel url params]
   (let [url'    (str "https://api.telegram.org/bot" token url)
         params' (assoc params :chat_id (str "@" channel))]
     (try
       (:body
         (http/post url'
           {:form-params  params'
            :content-type :json
            :as           :json-string-keys}))
       (catch Exception e
         (let [safe-url (str "https://api.telegram.org/bot<token>" url)]
           (cond
             (re-find #"Bad Request: message is not modified" (:body (ex-data e)))
             (log/log "Telegram request failed:" safe-url (pr-str params'))

             :else
             (do
               (log/log "Telegram request failed:" safe-url (pr-str params'))
               (throw e)))))))))


(defn post-media! [post]
  (let [video? (= :mime.type/video (-> post :post/media mime/type))
        media  (or
                 (when-not video?
                   (:post/media-full post))
                 (:post/media post))]
    (when media
      (let [url    (str config/hostname "/media/" (:media/url media))
            resp   (if video?
                     (post! "/sendVideo" {:video url})
                     (post! "/sendPhoto" {:photo url}))]
        (db/transact!
          [[:db/add (:db/id post) :post/crosspost -1]
           {:db/id                   -1
            :crosspost/type          :tg/media
            :crosspost.tg/channel    channel
            :crosspost.tg/message-id (get-in resp ["result" "message_id"])}])))))


(defn update-media! [post]
  (when token
    (let [video? (= :mime.type/video (-> post :post/media mime/type))
          media  (or
                   (when-not video?
                     (:post/media-full post))
                   (:post/media post))]
      (when media
        (let [url (str config/hostname "/media/" (:media/url media))]
          (doseq [crosspost (:post/crosspost post)
                  :when (= :tg/media (:crosspost/type crosspost))
                  :let [channel (:crosspost.tg/channel crosspost)
                        body    {:message_id (:crosspost.tg/message-id crosspost)
                                 :media      {:type  (if video? "video" "photo")
                                              :media url}}]]
            (post! channel "/editMessageMedia" body)))))))


(defn format-user [user]
  (if-some [telegram-user (:telegram/user (fragments/author-by :user user))]
    (str "@" telegram-user)
    (str "@" user)))


(defn post-text! [post]
  (when token
    (let [text (str (format-user (:post/author post)) ": " (:post/body post))
          body {:text text
                :disable_web_page_preview "true"}
          resp (post! "/sendMessage" body)]
      (db/transact!
        [[:db/add (:db/id post) :post/crosspost -1]
         {:db/id                   -1
          :crosspost/type          :tg/text
          :crosspost.tg/channel    channel
          :crosspost.tg/message-id (get-in resp ["result" "message_id"])}]))))


(defn update-text! [post]
  (when token
    (doseq [crosspost (:post/crosspost post)
            :when (= :tg/text (:crosspost/type crosspost))
            :let [channel (:crosspost.tg/channel crosspost)
                  text    (str (format-user (:post/author post)) ": " (:post/body post))
                  body    {:message_id (:crosspost.tg/message-id crosspost)
                           :text text
                           :disable_web_page_preview "true"}]]
      (post! channel "/editMessageText" body))))
