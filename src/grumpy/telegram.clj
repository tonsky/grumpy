(ns grumpy.telegram
  (:require
    [clojure.string :as str]
    [clj-http.client :as http]
    [clojure.data.json :as json]
    [grumpy.core :as grumpy]))


(def token (grumpy/slurp "grumpy_data/TELEGRAM_TOKEN"))
(def channel (or (grumpy/slurp "grumpy_data/TELEGRAM_CHANNEL") "whining_test"))


(defn post! [url params]
  (let [url'    (str "https://api.telegram.org/bot" token url)
        params' (assoc params :chat_id (str "@" channel))]
    (:body
      (http/post url'
        { :form-params  params'
          :content-type :json
          :as           :json-string-keys
          :coerce       :always}))))


(defn post-picture! [post]
  (let [picture (:picture-original post)]
    (cond
      grumpy/dev?    post
      (nil? token)   post
      (nil? picture) post
      :else
      (let [url  (str grumpy/hostname "/post/" (:id post) "/" (:url picture))
            resp (post! "/sendPhoto" {:photo url})]
        (update post :picture-original
          assoc :telegram/message_id (get-in resp ["result" "message_id"])
                :telegram/photo      (get-in resp ["result" "photo"]))))))


(defn format-user [user]
  (if-some [telegram-user (:telegram/user (grumpy/author-by :user user))]
    (str "@" telegram-user)
    (str "*" user "*")))


(defn post-text! [post]
  (cond
    (nil? token) post
    (str/blank? (:body post)) post
    :else
    (let [resp (post! "/sendMessage"
                 {:text (str (format-user (:author post)) ": " (:body post))
                  :parse_mode "Markdown"
                  :disable_web_page_preview "true"})]
        (assoc post
          :telegram/message_id (get-in resp ["result" "message_id"])))))


(defn update-text! [post]
  (let [id (:telegram/message_id post)]
    (cond
      (nil? token) post
      (nil? id)    post
      :else
      (let [_ (post! "/editMessageText"
                { :message_id id
                  :text (str (format-user (:author post)) ": " (:body post))
                  :parse_mode "Markdown"
                  :disable_web_page_preview "true" })]
        post))))