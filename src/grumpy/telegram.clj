(ns grumpy.telegram
  (:require
   [clojure.string :as str]
   [clj-http.client :as http]
   [grumpy.core :as grumpy]))


(def ^:dynamic token (grumpy/slurp "grumpy_data/TELEGRAM_TOKEN"))
(def ^:dynamic channels 
  (-> (or (grumpy/slurp "grumpy_data/TELEGRAM_CHANNEL") "grumpy_chat\nwhining_test")
      (str/split #"\s+")))


(defn post! [channel url params]
  (let [url'    (str "https://api.telegram.org/bot" token url)
        params' (assoc params :chat_id (str "@" channel))]
    (try
      (:body
        (http/post url'
          {:form-params  params'
           :content-type :json
           :as           :json-string-keys}))
      (catch Exception e
        (println "Telegram request failed:" url' (pr-str params'))
        (throw e)))))


(defn post-picture!
  ([post]
    (reduce post-picture! post channels))
  ([post channel]
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
                       :content.type/video (post! channel "/sendVideo" {:video url})
                       :content.type/image (post! channel "/sendPhoto" {:photo url}))]
         (update post :reposts grumpy/conjv
           { :type                :telegram/photo
             :telegram/channel    channel
             :telegram/message_id (get-in resp ["result" "message_id"])
             :telegram/photo      (get-in resp ["result" "photo"]) }))))))


(defn format-user [user]
  (if-some [telegram-user (:telegram/user (grumpy/author-by :user user))]
    (str "@" telegram-user)
    (str "@" user)))


(defn post-text!
  ([post]
   (reduce post-text! post channels))
  ([post channel]
   (cond
     (nil? token) post
     (str/blank? (:body post)) post
     :else
     (let [resp (post! channel "/sendMessage"
                  {:text (str (format-user (:author post)) ": " (:body post))
                   ; :parse_mode "Markdown"
                   :disable_web_page_preview "true"})]
       (update post :reposts grumpy/conjv
         {:type                :telegram/text
          :telegram/channel    channel
          :telegram/message_id (get-in resp ["result" "message_id"]) })))))


(defn update-text! [post]
  (when (some? token)
    (doseq [repost (:reposts post)
            :when  (= :telegram/text (:type repost))]
      (post! (:telegram/channel repost)
        "/editMessageText"
        { :message_id (:telegram/message_id repost)
          :text       (str (format-user (:author post)) ": " (:body post))
          ; :parse_mode "Markdown"
          :disable_web_page_preview "true" })))
  post)


(comment
  (binding [grumpy/hostname "https://grumpy.website"
            grumpy/dev? false
            channel "whining"]
    (-> (clojure.edn/read-string (slurp "grumpy_data/posts/0PZy7WhCE/post.edn"))
      #_(post-picture!)
      (post-text!)))
)