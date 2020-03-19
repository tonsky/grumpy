(ns grumpy.core.fragments
  (:require
   [clojure.string :as str]
   [rum.core :as rum]))


(def authors
  [{:email "niki@tonsky.me"
    :user  "nikitonsky"
    :telegram/user "nikitonsky" 
    :telegram/user-chat "232806939"
    :name  "Nikita Prokopov"
    :url   "https://twitter.com/nikitonsky"}
   {:email "freetonik@gmail.com"
    :user  "freetonik"
    :telegram/user "freetonik"
    :name  "Rakhim Davletkaliyev"
    :url   "https://twitter.com/freetonik"}
   {:email "ivan@grishaev.me"
    :user  "igrishaev"
    :telegram/user "igrishaev"
    :name  "Ivan Grishaev"
    :url   "https://grishaev.me/"}
   {:email "dmitrii@dmitriid.com"
    :user  "dmitriid"
    :telegram/user "mamutnespit"
    :telegram/user-chat "303519462"
    :name  "Dmitrii Dimandt"
    :url   "https://twitter.com/dmitriid"}])


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
              (let [norm-path     (re-find #"[^?#]+" path)
                    without-slash (str/replace norm-path #"/$" "")]
                (str "<a href=\"" href "\" target=\"_blank\">" without-slash "</a>"))))
          (str "<p>" paragraph "</p>"))))
    (str/join)))


(defn new? [post-id]
  (str/starts-with? post-id "@"))


(defn subscribe [*ref key]
  (rum/react (rum/cursor *ref key)))


(defn subscribe-in [*ref keys]
  (rum/react (rum/cursor-in *ref keys)))