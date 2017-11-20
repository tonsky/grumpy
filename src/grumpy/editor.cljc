(ns grumpy.editor
  (:require
    [clojure.string :as str]
    #?(:clj  [clojure.edn :as edn]
       :cljs [cljs.reader :as edn])
    #?(:cljs [goog.object :as gobj])
    #?(:cljs [cljs-drag-n-drop.core :as dnd])
    [rum.core :as rum]))


#?(:cljs (enable-console-print!))


#?(:cljs
(defn picture-input []
  (js/document.querySelector "input[name=picture]")))


#?(:cljs
(defn update-preview! [files *picture-url]
  (when-some [picture-url @*picture-url]
    (when (str/starts-with? picture-url "blob:")
      (js/URL.revokeObjectURL picture-url)))
  (if-some [file (when (> (alength files) 0)
                   (aget files 0))]
    (reset! *picture-url (js/URL.createObjectURL file))
    (reset! *picture-url nil))))


(rum/defcs editor < (rum/local nil ::picture-url)
  { :will-mount
    (fn [state]
      (let [*picture-url     (::picture-url state)
            [{:keys [post]}] (:rum/args state)]
        (when-some [[pic] (:pictures post)]
          (reset! *picture-url (str "/post/" (:id post) "/" (first (:pictures post))))))
      state) }
  #?(:cljs
  { :will-mount
    (fn [state]
      (let [*picture-url (::picture-url state)]
        (dnd/subscribe! js/document.documentElement ::editor
          { :start (fn [_] (js/document.body.classList.add "dragover"))
            :drop  (fn [_ files]
                     (update-preview! files *picture-url)
                     (gobj/set (picture-input) "files" files))
            :end   (fn [_] (js/document.body.classList.remove "dragover")) })
        state))
    :will-unmount
    (fn [state]
      (dnd/unsubscribe! js/document.documentElement ::editor)
      state) })
  [state {:keys [post-id post user]}]
  (let [create? (nil? post)
        {*picture-url ::picture-url} state]
    [:form.edit-post
      { :action   (str "/post/" post-id "/edit")
        :enc-type "multipart/form-data"
        :method   "post" }
      [:.form_row.edit-post_picture
        (merge
          { :class (when (nil? @*picture-url) "edit-post_picture-empty") }
          #?(:cljs
          { :on-click
            (fn [e]
              (.click (picture-input))
              (.preventDefault e)) }))
        (when-some [picture-url @*picture-url]
          [:img.post_img.edit-post_picture_img { :src picture-url }])]
      [:input.edit-post_file
        { :type "file"
          :name "picture"
          :on-change
          #?(:clj nil
             :cljs (fn [e]
                     (let [files (-> e (gobj/get "target") (gobj/get "files"))]
                       (update-preview! files *picture-url)))) }]
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