(ns grumpy.editor
  (:require
    #?(:clj  [clojure.edn :as edn]
       :cljs [cljs.reader :as edn])
    [rum.core :as rum]))


#?(:cljs (enable-console-print!))


(rum/defc editor [{:keys [post-id post user]}]
  (let [create? (nil? post)]
    [:form.edit-post
      { :action   (str "/post/" post-id "/edit")
        :enc-type "multipart/form-data"
        :method   "post" }
      [:.form_row.edit-post_picture
        [:input { :type "file" :name "picture"}]]
      [:.form_row
        [:textarea
          { :default-value (:body post "")
            :name          "body"
            :placeholder   "Be grumpy here..."
            :auto-focus    true }]]
      [:.form_row
        "Author: "
        [:input.edit-post_author
          { :type "text"
            :name "author"
            :default-value (or (:author post) user) }]]
      [:.form_row
        [:button (if create? "Grumpost now!" "Save")]]]))


#?(:cljs
(defn ^:export refresh []
  (let [mount (js/document.querySelector ".mount")
        data  (-> (.getAttribute mount "data")
                  (edn/read-string))]
    (rum/mount (editor data) mount))))


(comment
  (require 'grumpy.editor :reload))