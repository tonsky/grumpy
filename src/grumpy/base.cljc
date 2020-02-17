(ns grumpy.base)

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
    :url   "https://twitter.com/dmitriid"}
   {:email "ulyanovskui@gmail.com"
    :user  "andreivoronin"
    :telegram/user "andreuvoronin"
    :name  "Andrei Voronin"
    :url   "https://twitter.com/UlyanovskUI"}])


(defn author-by [attr value]
  (first (filter #(= (get % attr) value) authors)))


(defn zip [coll1 coll2]
  (map vector coll1 coll2))


(defn conjv [v x]
  (conj (vec v) x))


(defn update-some [m key f & args]
  (if-some [value (get m key)]
    (assoc m key (apply f value args))
    m))


(defn filtermv [pred m]
  (reduce-kv (fn [m k v] (if (pred v) (assoc m k v) m)) {} m))


(defn seek [pred coll]
  (reduce #(when (pred %2) (reduced %2)) nil coll))


(defn avatar-url [author]
  (if (some? (author-by :user author))
    (str "/static/" author ".jpg")
    "/static/guest.jpg"))

