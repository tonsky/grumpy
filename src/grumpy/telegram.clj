(ns grumpy.telegram
  (:require
    [clojure.string :as str]
    [clj-http.client :as http]
    [clojure.data.json :as json]
    [grumpy.core :as grumpy]))


(def ^:dynamic token (grumpy/slurp "grumpy_data/TELEGRAM_TOKEN"))
(def ^:dynamic channel (or (grumpy/slurp "grumpy_data/TELEGRAM_CHANNEL") "whining_test"))

(defn post! [url params]
  (let [url'    (str "https://api.telegram.org/bot" token url)
        params' (assoc params :chat_id (str "@" channel))]
    (try
      (:body
        (http/post url'
          { :form-params  params'
            :content-type :json
            :as           :json-string-keys}))
      (catch Exception e
        (println "Telegram request failed:" url' (pr-str params'))
        (throw e)))))


(defn post-picture! [post]
  (let [key (cond
              (contains? post :picture-original) :picture-original
              (contains? post :picture) :picture
              :else nil)]
    (cond
      grumpy/dev?  post
      (nil? token) post
      (nil? key)   post
      :else
      (let [picture (get post key)
            url     (str grumpy/hostname "/post/" (:id post) "/" (:url picture))
            resp    (case (grumpy/content-type picture)
                      :content.type/video (post! "/sendVideo" {:video url})
                      :content.type/image (post! "/sendPhoto" {:photo url}))]
        (update post key
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


(comment
  (binding [grumpy/hostname "https://grumpy.website"
            grumpy/dev? false
            channel "whining"]
    (-> (clojure.edn/read-string (slurp "grumpy_data/posts/0PVbF6Vrb/post.edn"))
      (post-picture!)
      (post-text!)))
)