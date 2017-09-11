(ns grumpy.core
  (:refer-clojure :exclude [slurp])
  (:require
    [rum.core :as rum]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [clojure.java.io :as io])
  (:import
    [java.util Date]
    [java.net URLEncoder]
    [org.joda.time DateTime DateTimeZone]
    [org.joda.time.format DateTimeFormat DateTimeFormatter ISODateTimeFormat]))


(.mkdirs (io/file "grumpy_data"))


(defmacro from-config [name default-value]
 `(let [file# (io/file "grumpy_data" ~name)]
    (when-not (.exists file#)
      (spit file# ~default-value))
    (clojure.core/slurp file#)))


(def authors (edn/read-string
               (from-config "AUTHORS" 
                 (pr-str #{ { :email "prokopov@gmail.com" :user "nikitonsky" :name "Никита Прокопов" }
                            { :email "freetonik@gmail.com" :user "freetonik" :name "Рахим Давлеткалиев" } }))))


(defn author-by [attr value]
  (first (filter #(= (get % attr) value) authors)))


(def hostname (from-config "HOSTNAME" "http://grumpy.website"))


(def dev? (= "http://localhost:8080" hostname))


(defn zip [coll1 coll2]
  (map vector coll1 coll2))


(defn now ^Date []
  (Date.))


(defn age [^Date inst]
  (- (.getTime (now)) (.getTime inst)))


(def ^:private date-formatter (DateTimeFormat/forPattern "dd.MM.YYYY"))
(def ^:private iso-formatter (DateTimeFormat/forPattern "yyyy-MM-dd'T'HH:mm:ss'Z'"))

(defn format-date [^Date inst]
  (.print ^DateTimeFormatter date-formatter (DateTime. inst)))


(defn format-iso-inst [^Date inst]
  (.print iso-formatter (DateTime. inst DateTimeZone/UTC)))


(defn encode-uri-component [s]
  (-> s
      (URLEncoder/encode "UTF-8")
      (str/replace #"\+"   "%20")
      (str/replace #"\%21" "!")
      (str/replace #"\%27" "'")
      (str/replace #"\%28" "(")
      (str/replace #"\%29" ")")
      (str/replace #"\%7E" "~")))


(defn url [path query]
  (str
    path
    "?"
    (str/join "&"
      (map
        (fn [[k v]]
          (str (name k) "=" (encode-uri-component v)))
        query))))


(def ^:const encode-table "-0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz")


(defn encode [num len]
  (loop [num  num
         list ()
         len  len]
    (if (== 0 len)
      (str/join list)
      (recur (bit-shift-right num 6)
             (let [ch (nth encode-table (bit-and num 0x3F))]
              (conj list ch))
             (dec len)))))


(defn redirect
  ([path]
    { :status 302
      :headers { "Location" path } })
  ([path query]
    { :status 302
      :headers { "Location" (url path query) }}))


(defn slurp [source]
  (try
    (clojure.core/slurp source)
    (catch Exception e
      nil)))


(defn get-post [post-id]
  (let [path (str "grumpy_data/posts/" post-id "/post.edn")]
    (some-> (io/file path)
            (slurp)
            (edn/read-string))))


(defn list-files
  ([dir] (seq (.list (io/file dir))))
  ([dir re]
    (seq
      (.list (io/file dir)
        (proxy [java.io.FilenameFilter] []
          (accept ^boolean [^java.io.File file ^String name]
            (boolean (re-matches re name))))))))
  

(defn post-ids []
  (->>
    (for [name (list-files "grumpy_data/posts")
          :let [child (io/file "grumpy_data/posts" name)]
          :when (.isDirectory child)]
      name)
    (sort)
    (reverse)))


(defn format-text [text]
  (->> (str/split text #"[\r\n]+")
    (map
      (fn [paragraph]
        (as-> paragraph paragraph
          ;; highlight links
          (str/replace paragraph #"https?://([^\s<>]+[^\s.,!?:;'\"\-<>()\[\]{}*_])"
            (fn [[href path]]
              (let [normalized-path (str/replace path #"/$" "")]
                (str "<a href=\"" href "\" target=\"_blank\">" (re-find #"[^?#]+" normalized-path) "</a>"))))
          (str "<p>" paragraph "</p>\n"))))
    (str/join)))


(def resource
  (cond-> (fn [name]
            (clojure.core/slurp (io/resource (str "static/" name))))
    (not dev?)
      (memoize)))


(rum/defc page [opts & children]
  (let [{:keys [title page styles scripts]
         :or {title "Ворчание ягнят"
              page  :other}} opts]
    [:html
      [:head
        [:meta { :http-equiv "Content-Type" :content "text/html; charset=UTF-8"}]
        [:meta { :name "viewport" :content "width=device-width, initial-scale=1.0"}]
        [:link { :href "/feed.xml" :rel "alternate" :title "Ворчание ягнят" :type "application/atom+xml" }]

        [:link { :href "/static/favicons/apple-touch-icon-152x152.png" :rel "apple-touch-icon-precomposed" :sizes "152x152" }]
        [:link { :href "/static/favicons/favicon-196x196.png" :rel "icon" :sizes "196x196" }]
        [:link { :href "/static/favicons/favicon-32x32.png"  :rel "icon" :sizes "32x32" }]

        [:title title]
        [:style { :type "text/css" :dangerouslySetInnerHTML { :__html (resource "styles.css") }}]
        (for [css styles]
          [:style { :type "text/css" :dangerouslySetInnerHTML { :__html (resource css) }}])]
      [:body.anonymous
        [:header
          (case page
            :index [:h1.title title [:a.title_new { :href "/new" } "+"]]
            :post  [:h1.title [:a {:href "/"} title ]]
                   [:h1.title [:a.title_back {:href "/"} "◄"] title])
          [:p.subtitle [:span " "]]]
        children
        (when (= page :index)
          [:.loader [:img { :src "/static/favicons/apple-touch-icon-152x152.png" }]])
        [:footer
          [:a { :href "https://twitter.com/nikitonsky" } "Никита Прокопов"]
          ", "
          [:a { :href "https://twitter.com/freetonik" } "Рахим Давлеткалиев"]
          ". 2017. All fights retarded."]    
        
        [:script {:dangerouslySetInnerHTML { :__html (resource "scripts.js") }}]
        (for [script scripts]
          [:script {:dangerouslySetInnerHTML { :__html (resource script) }}])]]))


(defn html-response [component]
  { :status 200
    :headers { "Content-Type" "text/html; charset=utf-8" }
    :body    (str "<!DOCTYPE html>\n" (rum/render-static-markup component)) })
