(ns grumpy.core.web
  (:require
    [clojure.java.io :as io]
    [grumpy.core.coll :as coll]
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


(defn moved-permanently
  ([path]
   {:status 301
    :headers {"Location" path}})
  ([path query]
   {:status 301
    :headers {"Location" (url/build path query)}}))


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


(defn menu [current]
  [:.menu
   (for [[page subpages url title] [[:index     #{:page :post} "/"          "Home"]
                                    [:search    #{}            "/search"    "Search"]
                                    #_[:subscribe #{}            "/subscribe" "How to Subscribe"]
                                    #_[:suggest   #{}            "/suggest"   "Suggest"]
                                    #_[:about     #{}            "/about"     "About"]
                                    (if (= :edit current)
                                      [:edit      #{}            nil       "Edit post"]
                                      [:new       #{}            "/new"    "New post"])]
         :let [id (str "menu_page_" (name page))]]
     (cond
       (= current page)
       [:span.no-select.selected {:id id} [:span title]]
       
       (subpages current)
       [:a.no-select.selected {:id id :href url} [:span title]]
       
       :else
       [:a.no-select {:id id :href url} [:span title]]))])


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
      [:list {:rel "me" :href "https://mastodon.online/@grumpy_website"}]

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
         :index [:h1.title "Grumpy Website"]
         #_else [:h1.title [:a {:href "/"} "Grumpy Website"]])
       (when subtitle?
         [:p.subtitle
          [:span.icon_rotate {:on-click "body_rotate()"}]
          [:span.subtitle-text " "]])
       (menu page)]
      children
      [:footer
       (interpose ", "
         (for [author fragments/authors]
           [:a { :href (:url author) } (:name author)]))
       " & contributors. 2018–2222. All fights retarded."]]]))


(defn html-response [component]
  {:status  200
   :headers { "Content-Type" "text/html; charset=utf-8" }
   :body    (str "<!DOCTYPE html>\n" (rum/render-static-markup component))})


(defn transit-response [payload]
  {:status  200
   :headers {"Content-Type" "application/transit+json; charset=utf-8"}
   :body    (transit/write-string payload)})
