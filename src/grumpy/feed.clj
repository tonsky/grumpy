(ns grumpy.feed
  (:require
   [clojure.string :as str]
   [clojure.data.xml :as xml]
   [rum.core :as rum]
   [ring.util.mime-type :as mime-type]
   [grumpy.core :as grumpy]
   [grumpy.authors :as authors]))


(def emit (comp xml/indent-str xml/sexp-as-element))


(defn max-date [posts]
  (->> posts
       (map :updated)
       (sort compare)
       last))


(defn feed [post-ids]
  (let [posts (map grumpy/get-post post-ids)
        updated (or (max-date posts)
                    (java.util.Date.))]
    (emit
     [:feed {:xmlns "http://www.w3.org/2005/Atom" :xml:lang "ru"}
      [:title {} "Grumpy Website"]
      [:subtitle {} "Are you sure you want to exist? — YES / NO"]
      [:icon {} (str grumpy/hostname "/static/favicons/favicon-32x32.png")]
      [:link {:type "application/atom+xml"
              :href (str grumpy/hostname "/feed.xml")
              :rel  "self"}]
      [:link {:type "text/html"
              :href (str grumpy/hostname "/")
              :rel  "alternate"}]
      [:id {} grumpy/hostname]
      [:updated {} (grumpy/format-iso-inst updated)]
      (for [author grumpy/authors]
        [:author {} [:name {} (:user author)]])
      (for [post posts
            :let [author (grumpy/author-by :user (:author post))
                  url    (str grumpy/hostname "/post/" (:id post))]]
        [:entry {}
         [:title {} (format "%s is being grumpy" (:author post))]
         [:link {:rel  "alternate"
                 :type "text/html"
                 :href url}]
         (when-let [name (some-> post :pictures first)]
           [:link {:rel  "enclosure"
                   :type (grumpy/mime-type name)
                   :href (str url "/" name)}])
         [:id {} url]
         [:published {} (grumpy/format-iso-inst (:created post))]
         [:updated {} (grumpy/format-iso-inst (:updated post))]
         [:author {} [:name {} (:author post)]]
         [:content {:type "text/html" :href url}
           (rum/render-static-markup
             (for [name (:pictures post)
                   :let [src (str url "/" name)]]
               [:p {}
                 (if (grumpy/video? name)
                   [:video {:autoplay "autoplay" :loop "loop"}
                     [:source {:type (grumpy/mime-type name) :src src}]]
                   [:img {:src src}])]))
           (grumpy/format-text
             (str
               (rum/render-static-markup
                 [:strong {} (format "%s: " (:author post))])
               (:body post)))]])])))


(defn sitemap [post-ids]
  (emit
   [:urlset {:xmlns "http://www.sitemaps.org/schemas/sitemap/0.9"}
     [:url {} [:loc {} grumpy/hostname]]
     (for [id post-ids]
       [:url {} [:loc {} (format "%s/post/%s" grumpy/hostname id)]])]))
