(ns grumpy.feed
  (:require
    [datascript.core :as d]
    [grumpy.core.config :as config]
    [grumpy.core.fragments :as fragments]
    [grumpy.core.mime :as mime]
    [grumpy.core.posts :as posts]
    [grumpy.core.time :as time]
    [grumpy.core.xml :as xml]
    [grumpy.db :as db]
    [rum.core :as rum]))

(defn max-date [posts]
  (->> posts
    (map :post/updated)
    (sort compare)
    last))

(defn feed [post-ids]
  (let [posts    (map #(d/entity (db/db) [:post/id %]) post-ids)
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
             :let [author     (fragments/author-by :user (:post/author post))
                   url        (if-some [old-id (:post/old-id post)]
                                (str hostname "/post/" (:post/old-id post))
                                (str hostname "/" (:post/id post)))
                   media      (:post/media post)
                   media-url  (when media
                                (if-some [old-url (:media/old-url media)]
                                  (str url "/" old-url)
                                  (str hostname "/media/" (:media/url media))))
                   media-mime (:media/content-type media)]]
         [:entry {}
          [:title {} (format "%s is being grumpy" (:post/author post))]
          [:link {:rel  "alternate"
                  :type "text/html"
                  :href url}]
          (when media
            [:link {:rel  "enclosure"
                    :type media-mime
                    :href media-url}])
          [:id {} url]
          [:published {} (time/format-iso-inst (:post/created post))]
          [:updated {} (time/format-iso-inst (:post/updated post))]
          [:author {} [:name {} (:post/author post)]]
          [:content {:type "html"}
           (rum/render-static-markup
             (when media
               [:p {}
                (case (some-> media mime/type)
                  :mime.type/video
                  [:video
                   {:autoplay    "autoplay"
                    :loop        "loop"
                    :muted       "muted"
                    :playsinline "playsinline"
                    :controls    "controls" ;; Feedly won’t autoplay :( https://github.com/tonsky/grumpy/issues/46
                    :style       {:max-width 550 :height "auto" :max-height 500}}
                   [:source {:type media-mime :src media-url}]]
                  :mime.type/image
                  (let [img (or
                              (when-some [w (:media/width media)]
                                (when-some [h (:media/height media)]
                                  (let [[w' h'] (fragments/fit w h 550 500)]
                                    [:img {:src media-url
                                           :style {:width  w'
                                                   :height h'}
                                           :width  w'
                                           :height h'}])))
                              [:img {:src media-url
                                     :style {:max-width 550
                                             :height "auto"
                                             :max-height 500}}])]
                    (if-some [full (:post/media-full post)]
                      [:a {:href
                           (if-some [old-url (:media/old-url full)]
                             (str url "/" old-url)
                             (str hostname "/media/" (:media/url full)))}
                       img]
                      img)))]))
           (fragments/format-text
             (str
               (rum/render-static-markup
                 [:strong {} (format "%s: " (:post/author post))])
               (:post/body post)))]])])))

(defn sitemap [post-ids]
  (let [hostname (config/get :grumpy.server/hostname)
        db       (db/db)]
    (xml/emit
      [:urlset {:xmlns "http://www.sitemaps.org/schemas/sitemap/0.9"}
       [:url {} [:loc {} hostname]]
       [:url {} [:loc {} (str hostname "/subscribe")]]
       [:url {} [:loc {} (str hostname "/suggest")]]
       [:url {} [:loc {} (str hostname "/about")]]
       (for [id post-ids]
         [:url {} [:loc {} (format "%s/%s" hostname id)]])
       (for [page (range (dec (posts/total-pages db)) 0 -1)]
         [:url {} [:loc {} (format "%s/page/%s" hostname page)]])])))
