(ns grumpy.core.url
  (:require
   [clojure.string :as str]))


(defn encode-uri-component [s]
  (-> #?(:clj (java.net.URLEncoder/encode ^String s "UTF-8")
         :cljs (js/encodeURIComponent s))
    (str/replace #"\+"   "%20")
    (str/replace #"\%21" "!")
    (str/replace #"\%27" "'")
    (str/replace #"\%28" "(")
    (str/replace #"\%29" ")")
    (str/replace #"\%7E" "~")))


(defn build [path query]
  (str
    path
    "?"
    (str/join "&"
      (map
        (fn [[k v]]
          (str (name k) "=" (encode-uri-component v)))
        query))))
