(ns grumpy.feed
  (:require
   [clojure.string :as str]
   [clojure.xml :refer [emit-element]]
   [clojure.data.xml :refer [indent-str emit-str sexp-as-element]]

   [grumpy.core :as grumpy]
   [grumpy.authors :as authors]))

;; swap emit-str to indent-str for debug
(def emit (comp emit-str sexp-as-element))
(def emit-el (comp emit-element sexp-as-element))

(defn get-ext [filename]
  (keyword (str/lower-case (last (str/split filename #"\.")))))

(defn guess-mime [filename]
  (case (get-ext filename)
    :mp4 "video/mp4"
    (:jpg :jpeg) "image/jpeg"
    :gif "image/gif"
    :png "image/png"
    "application/octet-stream"))

(defn get-max-date [posts]
  (->> posts
       (map :updated)
       (sort compare)
       last))

(defn feed [post-ids]
  (let [posts (map grumpy/get-post post-ids)
        updated (or (get-max-date posts)
                    (java.util.Date.))]
    (emit
     [:feed
      {:xmlns "http://www.w3.org/2005/Atom"
       :xml:lang "ru"}
      [:title nil "Ворчание ягнят"]
      [:subtitle nil "Are you sure you want to exist? — YES / NO"]
      [:icon nil (str grumpy/hostname "/static/favicons/favicon-32x32.png")]
      [:link {:type "application/atom+xml"
              :href (str grumpy/hostname "/feed.xml")
              :rel "self"}]
      [:link {:type "text/html"
              :href (str grumpy/hostname "/")
              :rel "alternate"}]
      [:id nil grumpy/hostname]
      [:updated nil (grumpy/format-iso-inst updated)]
      (for [author grumpy/authors]
        [:author nil [:name nil (:user author)]])
      (for [post posts
            :let [author (grumpy/author-by :user (:author post))
                  url (str grumpy/hostname "/post/" (:id post))]]
        [:entry nil
         [:title nil (format "%s ворчит" (:author post))]
         [:link {:rel "alternate"
                 :type "text/html"
                 :href url}]
         (when-let [name (some-> post :pictures first)]
           [:link {:rel "enclosure"
                   :type (guess-mime name)
                   :href (str url "/" name)}])
         [:id nil url]
         [:published nil (grumpy/format-iso-inst (:created post))]
         [:updated nil (grumpy/format-iso-inst (:updated post))]
         [:author nil [:name nil (:author post)]]
         [:content {:type "text/html" :href url}
          [:-cdata
           (str
            (when-let [pictures (-> post :pictures not-empty)]
              (emit-el
               (for [name pictures
                     :let [src (str url "/" name)]]
                 (if (str/ends-with? name ".mp4")
                   [:p nil
                    [:video {:autoplay "autoplay" :loop "loop"}
                     [:source {:type "video/mp4" :src src}]]]
                   [:p nil [:img {:src src}]]))))
            (emit-el [:strong nil (format "%s: " (:author post))])
            (grumpy/format-text (:body post)))]]])])))

(defn sitemap [post-ids]
  (emit
   [:urlset {:xmlns "http://www.sitemaps.org/schemas/sitemap/0.9"}
    [:url nil [:loc nil grumpy/hostname]]
    (for [id post-ids]
      [:url nil [:loc nil (format "%s/post/%s" grumpy/hostname id)]])]))
