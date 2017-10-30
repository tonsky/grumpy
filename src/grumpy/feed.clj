(ns grumpy.feed
  (:require
   [clojure.data.xml :refer [element cdata indent-str]]
   [grumpy.core :as grumpy]
   [grumpy.authors :as authors]))

(defn url-node [url]
  (element :url nil (element :loc nil url)))

(defn sitemap [post-ids]
  (indent-str
   (element
    :urlset {:xmlns "http://www.sitemaps.org/schemas/sitemap/0.9"}
    (url-node grumpy/hostname)
    (for [id post-ids]
      (url-node (format "%s/post/%s" grumpy/hostname id))))))
