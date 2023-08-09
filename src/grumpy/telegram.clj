(ns grumpy.telegram
  (:require
    [clj-http.client :as http]
    [clojure.string :as str]
    [grumpy.core.coll :as coll]
    [grumpy.core.config :as config]
    [grumpy.core.fragments :as fragments]
    [grumpy.core.log :as log]
    [grumpy.core.mime :as mime]))


(def ^:dynamic token
  (config/get-optional ::token))


(def ^:dynamic channels
  (or (config/get-optional ::channels)
    #{"grumpy_chat" "grumpy_test"}))


(defn post! [channel url params]
  (let [url'    (str "https://api.telegram.org/bot" token url)
        params' (assoc params :chat_id channel)]
    (try
      (:body
        (http/post url'
          {:form-params  params'
           :content-type :json
           :as           :json-string-keys}))
      (catch Exception e
        (cond
          (re-find #"Bad Request: message is not modified" (:body (ex-data e)))
          (log/log "Telegram request failed:" url' (pr-str params'))

          :else
          (do
            (log/log "Telegram request failed:" url' (pr-str params'))
            (throw e)))))))


(defn post-picture!
  ([post]
   (reduce post-picture! post channels))
  ([post channel]
   (let [video? (= :mime.type/video (some-> post :picture mime/type))
         key    (cond
                  video? :picture
                  (contains? post :picture-original) :picture-original
                  (contains? post :picture) :picture
                  :else nil)]
     (cond
       config/dev?  post
       (nil? token) post
       (nil? key)   post
       :else
       (let [picture (get post key)
             url     (str (config/get :grumpy.server/hostname) "/post/" (:id post) "/" (:url picture))
             resp    (case (mime/type picture)
                       :mime.type/video (post! (str "@" channel) "/sendVideo" {:video url})
                       :mime.type/image (post! (str "@" channel) "/sendPhoto" {:photo url}))]
         (update post :reposts coll/conjv
           { :type                :telegram/photo
            :telegram/channel    channel
            :telegram/message_id (get-in resp ["result" "message_id"])
            :telegram/photo      (get-in resp ["result" "photo"]) }))))))


(defn format-user [user]
  (if-some [telegram-user (:telegram/user (fragments/author-by :user user))]
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
     (let [resp (post! (str "@" channel) "/sendMessage"
                  {:text (str (format-user (:author post)) ": " (:body post))
                   ; :parse_mode "Markdown"
                   :disable_web_page_preview "true"})]
       (update post :reposts coll/conjv
         {:type                :telegram/text
          :telegram/channel    channel
          :telegram/message_id (get-in resp ["result" "message_id"]) })))))


(defn update-text! [post]
  (when (some? token)
    (doseq [repost (:reposts post)
            :when  (= :telegram/text (:type repost))]
      (post! (str "@" (:telegram/channel repost))
        "/editMessageText"
        { :message_id (:telegram/message_id repost)
         :text       (str (format-user (:author post)) ": " (:body post))
         ; :parse_mode "Markdown"
         :disable_web_page_preview "true" })))
  post)


(comment
  ;; text
  (post! "@grumpy_test" "/sendMessage" {:text "hey"})
  {"ok" true,
   "result"
   {"message_id" 54,
    "sender_chat"
    {"id" -1001150152488,
     "title" "Grumpy Website Test",
     "username" "grumpy_test",
     "type" "channel"},
    "chat"
    {"id" -1001150152488,
     "title" "Grumpy Website Test",
     "username" "grumpy_test",
     "type" "channel"},
    "date" 1691602190,
    "text" "hey"}}
  
  ;; photo
  (post! "@grumpy_test" "/sendPhoto" {:photo "https://grumpy.website/post/0ZotHcUFI/NbPTyeX.fit.jpeg"})
  {"ok" true,
   "result"
   {"message_id" 51,
    "sender_chat"
    {"id" -1001150152488,
     "title" "Grumpy Website Test",
     "username" "grumpy_test",
     "type" "channel"},
    "chat"
    {"id" -1001150152488,
     "title" "Grumpy Website Test",
     "username" "grumpy_test",
     "type" "channel"},
    "date" 1691601229,
    "photo"
    [{"file_id"
      "AgACAgQAAx0ERI3vKAADM2TTyU17gVHLzgxhVoQuqJWFeU_-AAJfsTEb0kilUjvvICWcwhwvAQADAgADcwADMAQ",
      "file_unique_id" "AQADX7ExG9JIpVJ4",
      "file_size" 626,
      "width" 90,
      "height" 37}
     {"file_id"
      "AgACAgQAAx0ERI3vKAADM2TTyU17gVHLzgxhVoQuqJWFeU_-AAJfsTEb0kilUjvvICWcwhwvAQADAgADbQADMAQ",
      "file_unique_id" "AQADX7ExG9JIpVJy",
      "file_size" 6515,
      "width" 320,
      "height" 132}
     {"file_id"
      "AgACAgQAAx0ERI3vKAADM2TTyU17gVHLzgxhVoQuqJWFeU_-AAJfsTEb0kilUjvvICWcwhwvAQADAgADeAADMAQ",
      "file_unique_id" "AQADX7ExG9JIpVJ9",
      "file_size" 28971,
      "width" 800,
      "height" 331}
     {"file_id"
      "AgACAgQAAx0ERI3vKAADM2TTyU17gVHLzgxhVoQuqJWFeU_-AAJfsTEb0kilUjvvICWcwhwvAQADAgADeQADMAQ",
      "file_unique_id" "AQADX7ExG9JIpVJ-",
      "file_size" 37884,
      "width" 1100,
      "height" 455}]}}
  
  ;; video
  (spit "resp.edn" (post! "@grumpy_test" "/sendVideo" {:video "https://grumpy.website/post/0ZoEEFx5A/NbFCxCE.fit.mp4"}))
  {"ok" true,
   "result"
   {"message_id" 53,
    "sender_chat"
    {"id" -1001150152488,
     "title" "Grumpy Website Test",
     "username" "grumpy_test",
     "type" "channel"},
    "chat"
    {"id" -1001150152488,
     "title" "Grumpy Website Test",
     "username" "grumpy_test",
     "type" "channel"},
    "date" 1691601322,
    "animation"
    {"file_unique_id" "AgADfgQAAqwTjFI",
     "width" 1100,
     "file_id"
     "CgACAgQAAx0ERI3vKAADNWTTyaq9FBCBLrtCzKZAG3GSz27tAAJ-BAACrBOMUjcKxong8dCKMAQ",
     "file_size" 285224,
     "height" 710,
     "thumb"
     {"file_id"
      "AAMCBAADHQREje8oAAM1ZNPJqr0UEIEuu0LMpkAbcZLPbu0AAn4EAAKsE4xSNwrGieDx0IoBAAdtAAMwBA",
      "file_unique_id" "AQADfgQAAqwTjFJy",
      "file_size" 12447,
      "width" 320,
      "height" 207},
     "thumbnail"
     {"file_id"
      "AAMCBAADHQREje8oAAM1ZNPJqr0UEIEuu0LMpkAbcZLPbu0AAn4EAAKsE4xSNwrGieDx0IoBAAdtAAMwBA",
      "file_unique_id" "AQADfgQAAqwTjFJy",
      "file_size" 12447,
      "width" 320,
      "height" 207},
     "duration" 15,
     "mime_type" "video/mp4",
     "file_name" "NbFCxCE.fit.mp4"},
    "document"
    {"file_name" "NbFCxCE.fit.mp4",
     "mime_type" "video/mp4",
     "thumbnail"
     {"file_id"
      "AAMCBAADHQREje8oAAM1ZNPJqr0UEIEuu0LMpkAbcZLPbu0AAn4EAAKsE4xSNwrGieDx0IoBAAdtAAMwBA",
      "file_unique_id" "AQADfgQAAqwTjFJy",
      "file_size" 12447,
      "width" 320,
      "height" 207},
     "thumb"
     {"file_id"
      "AAMCBAADHQREje8oAAM1ZNPJqr0UEIEuu0LMpkAbcZLPbu0AAn4EAAKsE4xSNwrGieDx0IoBAAdtAAMwBA",
      "file_unique_id" "AQADfgQAAqwTjFJy",
      "file_size" 12447,
      "width" 320,
      "height" 207},
     "file_id"
     "CgACAgQAAx0ERI3vKAADNWTTyaq9FBCBLrtCzKZAG3GSz27tAAJ-BAACrBOMUjcKxong8dCKMAQ",
     "file_unique_id" "AgADfgQAAqwTjFI",
     "file_size" 285224}}}

  
  (post! "@grumpy_test" "/editMessageMedia"
    {:chat_id "@grumpy_test"
     :message_id 51
     :media {:type "photo"
             :media "https://grumpy.website/post/0ZnG78YKt/Nb02EKK.fit.jpeg"}})
  (http/post (str "https://api.telegram.org/bot" token "/getUpdates") {:form-params {} :content-type :json :as :json-string-keys})
  )