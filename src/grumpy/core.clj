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


(defn post-ids []
  (->>
    (for [name (seq (.list (io/file "grumpy_data/posts")))
          :let [child (io/file "grumpy_data/posts" name)]
          :when (.isDirectory child)]
      name)
    (sort)
    (reverse)))


(def ^:private script (clojure.core/slurp (io/resource "script.js")))


(rum/defc page [opts & children]
  (let [{:keys [title index? styles]
         :or {title  "Ворчание ягнят"
              index? false}} opts]
    [:html
      [:head
        [:meta { :http-equiv "Content-Type" :content "text/html; charset=UTF-8"}]
        [:meta { :name "viewport" :content "width=device-width, initial-scale=1.0"}]
        [:link { :href "/feed.xml" :rel "alternate" :title "Ворчание ягнят" :type "application/atom+xml" }]

        [:title title]
        [:link { :rel "stylesheet" :type "text/css" :href "/static/styles.css" }]
        (for [css styles]
          [:link { :rel "stylesheet" :type "text/css" :href (str "/static/" styles) }])]
      [:body.anonymous
        [:header
          (if index?
            [:h1.title title [:a.title_new { :href "/new" } "+"]]
            [:h1.title [:a.title_back {:href "/"} "◄"] title])
          [:p.subtitle [:span " "]]]
        children
      [:footer
        [:a { :href "https://twitter.com/nikitonsky" } "Никита Прокопов"]
        ", "
        [:a { :href "https://twitter.com/freetonik" } "Рахим Давлеткалиев"]
        ". 2017. All fights retarded."]    
      
      [:script {:dangerouslySetInnerHTML { :__html script}}]]]))


(defn html-response [component]
  { :status 200
    :headers { "Content-Type" "text/html; charset=utf-8" }
    :body    (str "<!DOCTYPE html>\n" (rum/render-static-markup component)) })
