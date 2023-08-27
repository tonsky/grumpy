(ns grumpy.core.fragments
  (:require
    [clojure.string :as str]
    [grumpy.core.mime :as mime]
    #?(:clj [grumpy.core.time :as time])
    [rum.core :as rum]))


(def authors
  [{:user  "nikitonsky"
    :telegram/user "nikitonsky" 
    :telegram/user-chat "232806939"
    :name  "Nikita Prokopov"
    :url   "https://twitter.com/nikitonsky"}
   {:user  "dmitriid"
    :telegram/user "mamutnespit"
    :telegram/user-chat "303519462"
    :name  "Dmitrii Dimandt"
    :url   "https://twitter.com/dmitriid"}
   {:user  "freetonik"
    :telegram/user "freetonik"
    :name  "Rakhim Davletkaliyev"
    :url   "https://twitter.com/freetonik"}])


(defn author-by [attr value]
  (first (filter #(= (get % attr) value) authors)))


(defn avatar-url [author]
  (if (some? (author-by :user author))
    (str "/static/" author ".jpg")
    "/static/guest.jpg"))


(defn fit [x y maxx maxy]
  (cond
    (> x maxx)
    (fit maxx (* y (/ maxx x)) maxx maxy)
    (> y maxy)
    (fit (* x (/ maxy y)) maxy maxx maxy)
    :else
    [(int x) (int y)]))


(defn format-text [text]
  (->> (str/split text #"[\r\n]+")
    (map
      (fn [paragraph]
        (as-> paragraph paragraph
          ;; highlight links
          (str/replace paragraph #"https?://([^\s<>]+[^\s.,!?:;'\"<>()\[\]{}*])"
            (fn [[href path]]
              (let [norm-path     (re-find #"[^#]+" path)
                    without-slash (str/replace norm-path #"/$" "")]
                (str "<a href=\"" href "\" target=\"_blank\">" without-slash "</a>"))))
          (str "<p>" paragraph "</p>"))))
    (str/join)))


(defn subscribe [*ref key]
  (rum/react (rum/cursor *ref key)))


(defn subscribe-in [*ref keys]
  (rum/react (rum/cursor-in *ref keys)))


#?(:clj
   (defn post [post]
     [:.post
      {:data-id (:post/id post)}
      [:.post_side
       [:img.post_avatar {:src (avatar-url (:post/author post))}]]
      [:.post_content
       (when-some [media (:post/media post)]
         (let [src  (str "/media/" (:media/url media))
               href (if-some [full (:post/media-full post)]
                      (str "/media/" (:media/url full))
                      src)]
           (case (mime/type media)
             :mime.type/video
             [:.post_video_outer
              [:video.post_video
               {:autoplay true
                :muted true
                :loop true
                :preload "auto"
                :playsinline true
                :onplay "toggle_video(this.parentNode, true);" }
               [:source
                {:type (mime/mime-type (:media/url media))
                 :src src}]]
              [:.controls
               [:button.paused {:onclick "toggle_video(this.parentNode.parentNode);"}]
               [:button.fullscreen {:onclick "toggle_video_fullscreen(this.parentNode.parentNode);"}]]]
             :mime.type/image
             (or
               (when-some [w (:media/width media)]
                 (when-some [h (:media/height media)]
                   (let [[w' h'] (fit w h 550 500)]
                     [:div {:style {:max-width w'}}
                      [:a.post_img.post_img-fix
                       {:href href
                        :target "_blank"
                        :style {:padding-bottom (-> (/ h w) (* 100) (double) (str "%"))}}
                       [:img {:src src}]]])))
               [:a.post_img.post_img-flex 
                {:href href
                 :target "_blank"}
                [:img {:src src}]]))))
       [:.post_body
        {:dangerouslySetInnerHTML
         {:__html
          (format-text
            (str "<span class=\"post_author\">" (:post/author post) ": </span>" (:post/body post)))}}]
       [:p.post_meta
        (time/format-date (:post/created post))
        " // " [:a {:href (str "/" (:post/id post))} "Permalink"]
        [:a.post_meta_edit {:href (str "/" (:post/id post) "/edit")} "Edit"]]]]))

