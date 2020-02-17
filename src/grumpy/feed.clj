(ns grumpy.feed
  (:require
   [clojure.string :as str]
   [rum.core :as rum]
   [ring.util.mime-type :as mime-type]
   [grumpy.xml :as xml]
   [grumpy.base :as base]
   [grumpy.time :as time]
   [grumpy.core :as core]
   [grumpy.config :as config]))


(defn max-date [posts]
  (->> posts
       (map :updated)
       (sort compare)
       last))


(defn feed [post-ids]
  (let [posts    (map core/get-post post-ids)
        updated  (or (max-date posts) (time/now))
        hostname (config/get ::config/hostname)]
    (xml/emit
     [:feed {:xmlns    "http://www.w3.org/2005/Atom"
             :xml:lang "ru"
             :xml:base (str hostname "/")}
      [:title {} "Grumpy Website"]
      [:subtitle {} "Do you want to cancel? – YES / CANCEL"]
      [:icon {} (str hostname "/static/favicons/favicon-32x32.png")]
      [:link {:type "application/atom+xml"
              :href (str hostname "/feed.xml")
              :rel  "self"}]
      [:link {:type "text/html"
              :href (str hostname "/")
              :rel  "alternate"}]
      [:id {} (str hostname "/")]
      [:updated {} (time/format-iso-inst updated)]
      (for [author base/authors]
        [:author {} [:name {} (:user author)]])
      (for [post posts
            :let [author (base/author-by :user (:author post))
                  url    (str hostname "/post/" (:id post))]]
        [:entry {}
         [:title {} (format "%s is being grumpy" (:author post))]
         [:link {:rel  "alternate"
                 :type "text/html"
                 :href url}]
         (when-some [pic (some-> post :picture)]
           [:link {:rel  "enclosure"
                   :type (core/mime-type (:url pic))
                   :href (str url "/" (:url pic))}])
         [:id {} url]
         [:published {} (time/format-iso-inst (:created post))]
         [:updated {} (time/format-iso-inst (:updated post))]
         [:author {} [:name {} (:author post)]]
         [:content {:type "text/html"}
           (rum/render-static-markup
             (when-some [pic (:picture post)]
               (let [src (str url "/" (:url pic))]
                 [:p {}
                   (case (core/content-type pic)
                     :content.type/video
                       [:video
                         { :autoplay "autoplay"
                           :loop "loop"
                           :style { :max-width 550 :height "auto" :max-height 500 }}
                         [:source {:type (core/mime-type (:url pic)) :src src}]]
                     :content.type/image
                       (let [img (if-some [[w h] (:dimensions pic)]
                                   (let [[w' h'] (core/fit w h 550 500)]
                                     [:img { :src src
                                             :style { :width w'
                                                      :height h' }
                                             :width w'
                                             :height h' }])
                                   [:img { :src src
                                           :style { :max-width 550
                                                    :height "auto"
                                                    :max-height 500 }}])]
                       (if-some [orig (:picture-original post)]
                         [:a { :href (str url "/" (:url orig)) } img]
                         img)))])))
           (core/format-text
             (str
               (rum/render-static-markup
                 [:strong {} (format "%s: " (:author post))])
               (:body post)))]])])))


(defn sitemap [post-ids]
  (let [hostname (config/get ::config/hostname)]
    (xml/emit
      [:urlset {:xmlns "http://www.sitemaps.org/schemas/sitemap/0.9"}
       [:url {} [:loc {} hostname]]
       (for [id post-ids]
         [:url {} [:loc {} (format "%s/post/%s" hostname id)]])])))
