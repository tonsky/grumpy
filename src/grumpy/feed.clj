(ns grumpy.feed
  (:require
   [grumpy.core.config :as config]
   [grumpy.core.fragments :as fragments]
   [grumpy.core.mime :as mime]
   [grumpy.core.posts :as posts]
   [grumpy.core.time :as time]
   [grumpy.core.xml :as xml]
   [rum.core :as rum]))


(defn max-date [posts]
  (->> posts
       (map :updated)
       (sort compare)
       last))


(defn feed [post-ids]
  (let [posts    (map posts/load post-ids)
        updated  (or (max-date posts) (time/now))
        hostname (config/get :grumpy.server/hostname)]
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
      (for [author fragments/authors]
        [:author {} [:name {} (:user author)]])
      (for [post posts
            :let [author (fragments/author-by :user (:author post))
                  url    (str hostname "/post/" (:id post))]]
        [:entry {}
         [:title {} (format "%s is being grumpy" (:author post))]
         [:link {:rel  "alternate"
                 :type "text/html"
                 :href url}]
         (when-some [pic (some-> post :picture)]
           [:link {:rel  "enclosure"
                   :type (mime/mime-type (:url pic))
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
                   (case (mime/type pic)
                     :mime.type/video
                       [:video
                        {:autoplay    "autoplay"
                         :loop        "loop"
                         :muted       "muted"
                         :playsinline "playsinline"
                         :controls    "controls" ;; Feedly won’t autoplay :( https://github.com/tonsky/grumpy/issues/46
                         :style       {:max-width 550 :height "auto" :max-height 500}}
                        [:source {:type (mime/mime-type (:url pic)) :src src}]]
                     :mime.type/image
                       (let [img (if-some [[w h] (:dimensions pic)]
                                   (let [[w' h'] (fragments/fit w h 550 500)]
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
           (fragments/format-text
             (str
               (rum/render-static-markup
                 [:strong {} (format "%s: " (:author post))])
               (:body post)))]])])))


(defn sitemap [post-ids]
  (let [hostname (config/get :grumpy.server/hostname)]
    (xml/emit
      [:urlset {:xmlns "http://www.sitemaps.org/schemas/sitemap/0.9"}
       [:url {} [:loc {} hostname]]
       (for [id post-ids]
         [:url {} [:loc {} (format "%s/post/%s" hostname id)]])])))
