(ns grumpy.core.web
  (:require
   [clojure.java.io :as io]
   [grumpy.core.config :as config]
   [grumpy.core.files :as files]
   [grumpy.core.fragments :as fragments]
   [grumpy.core.transit :as transit]
   [grumpy.core.url :as url]
   [ring.util.response :as response]
   [rum.core :as rum]))


(def empty-success-response
  {:status 200
   :headers {"content-type" "text/plain"}})


(defn redirect
  ([path]
    { :status 302
      :headers { "Location" path } })
  ([path query]
    { :status 302
      :headers { "Location" (url/build path query) }}))


(def resource
  (cond-> (fn [name]
            (slurp (io/resource (str "static/" name))))
    (not config/dev?) (memoize)))


(defn first-file [& paths]
  (reduce
    (fn [resp path]
      (let [file (io/file path)]
        (if (.exists file)
          (reduced (response/file-response path))
          resp)))
    {:status 404} paths))


(def checksum-resource
  (if config/dev?
    identity
    (memoize
      (fn [res]
        (let [contents (files/slurp (io/resource res))
              digest   (.digest (java.security.MessageDigest/getInstance "SHA-1") (.getBytes ^String contents))
              buffer   (StringBuilder. (* 2 (alength digest)))]
          (areduce digest i s buffer
            (doto s
              (.append (Integer/toHexString (unsigned-bit-shift-right (bit-and (aget digest i) 0xF0) 4)))
              (.append (Integer/toHexString (bit-and (aget digest i) 0x0F)))))
          (str res "?checksum=" (str buffer)))))))


(def style
  (memoize
    (fn [name]
      (if config/dev?
        [:link { :rel "stylesheet" :type "text/css" :href (str "/static/" name) }]
        (let [content (slurp (io/resource (str "static/" name)))]
          [:style { :type "text/css" :dangerouslySetInnerHTML { :__html content }}])))))


(rum/defc page [opts & children]
  (let [{:keys [title page subtitle? styles scripts]
         :or {title     "Grumpy Website"
              page      :other
              subtitle? true}} opts]
    [:html
      [:head
        [:meta {:http-equiv "Content-Type" :content "text/html; charset=UTF-8"}]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
        [:link {:href "/feed.xml" :rel "alternate" :title "Grumpy Website" :type "application/atom+xml"}]

        [:link {:href "/static/favicons/apple-touch-icon-152x152.png" :rel "apple-touch-icon-precomposed" :sizes "152x152"}]
        [:link {:href "/static/favicons/favicon-196x196.png" :rel "icon" :sizes "196x196"}]
        [:link {:href "/static/favicons/favicon-32x32.png" :rel "icon" :sizes "32x32"}]
        [:link {:rel "preload" :href "/static/favicons/apple-touch-icon-152x152.png" :as "image"}]

        [:title title]
        (style "styles.css")
        (for [css styles]
          (style css))
        [:script {:dangerouslySetInnerHTML { :__html (resource "scripts.js") }}]
        (for [script scripts]
          [:script {:dangerouslySetInnerHTML { :__html (resource script) }}])]
      [:body.anonymous
        [:header
          (case page
            :index [:h1.title title [:a.title_new { :href "/new" } "+"]]
            :post  [:h1.title [:a {:href "/"} title ]]
                   [:h1.title [:a.title_back {:href "/"} "◄"] title])
          (when subtitle?
            [:p.subtitle
              [:span.icon_rotate {:on-click "body_rotate()"}]
              [:span.subtitle-text " "]])]
        children
        (when (= page :index)
          [:.loader-block.row.center [:.loader]])
        [:footer
          (interpose ", "
            (for [author fragments/authors]
              [:a { :href (:url author) } (:name author)]))
          " and contributors. 2018–2222. All fights retarded."]]]))


(defn html-response [component]
  {:status  200
   :headers { "Content-Type" "text/html; charset=utf-8" }
   :body    (str "<!DOCTYPE html>\n" (rum/render-static-markup component))})


(defn transit-response [payload]
  {:status  200
   :headers {"Content-Type" "application/transit+json; charset=utf-8"}
   :body    (transit/write-transit-str payload)})
