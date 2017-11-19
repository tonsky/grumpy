(ns grumpy.editor
  (:require
    [clojure.string :as str]
    #?(:clj  [clojure.edn :as edn]
       :cljs [cljs.reader :as edn])
    #?(:cljs [goog.object :as gobj])
    [rum.core :as rum]))


#?(:cljs (enable-console-print!))


#?(:cljs
(defn kill-timer! [*drop-timer]
  (when-some [t @*drop-timer]
    (js/clearTimeout t)
    (reset! *drop-timer nil))))


#?(:cljs
(defn on-drag-start! [e]
  (js/setTimeout #(js/document.body.classList.add "dragover") 0)))


#?(:cljs
(defn on-drag-end! [*drop-timer]
  (kill-timer! *drop-timer)
  (js/document.body.classList.remove "dragover")))


#?(:cljs
(defn picture-input []
  (js/document.querySelector "input[name=picture]")))


#?(:cljs
(defn update-preview! [files *picture-url]
  (let [file  (when (> (alength files) 0)
                (aget files 0))
        picture-url @*picture-url]
    (when picture-url
      (when (str/starts-with? picture-url "blob:")
        (js/URL.revokeObjectURL picture-url)))
    (if file
      (reset! *picture-url (js/URL.createObjectURL file))
      (reset! *picture-url nil)))))


#?(:cljs
(def handlers
  { ::dragstart
    (fn [e _ *ignore? _]
      (reset! *ignore? true))
    ::dragend
    (fn [e _ *ignore? _]
      (reset! *ignore? false))
    ::dragover
    (fn [e *drop-timer *ignore? _]
      (when-not @*ignore?
        (.preventDefault e)
        (when (nil? @*drop-timer)
          (on-drag-start! e))
        (kill-timer! *drop-timer)
        (reset! *drop-timer
          (js/setTimeout
            (fn []
              (reset! *drop-timer nil)
              (on-drag-end! *drop-timer))
            500))))
    ::drop
    (fn [e *drop-timer *ignore? *picture-url]
      (when-not @*ignore?
        (.preventDefault e)
        (let [files (-> e (gobj/get "dataTransfer") (gobj/get "files"))]
          (update-preview! files *picture-url)
          (gobj/set (picture-input) "files" files))
        (on-drag-end! *drop-timer))) }))


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
      (let [*drop-timer  (atom nil)
            *ignore?     (atom false)
            *picture-url (::picture-url state)]
        (assoc state
          ::drop-timer *drop-timer
          ::ignore?    *ignore?
          ::handlers
            (into {}
              (for [[key handler] handlers
                    :let [f #(handler % *drop-timer *ignore? *picture-url)]]
                (do 
                  (js/document.documentElement.addEventListener (name key) f false)
                  [key f]))))))
    :will-unmount
    (fn [state]
      (on-drag-end! (::drop-timer state))
      (doseq [[key f] (::handlers state)]
        (js/document.documentElement.removeEventListener (name key) f false))
      (dissoc state ::drop-timer ::ignore? ::handlers)) })
  [state {:keys [post-id post user]}]
  (let [create? (nil? post)
        {*picture-url ::picture-url} state]
    [:form.edit-post
      { :action   (str "/post/" post-id "/edit")
        :enc-type "multipart/form-data"
        :method   "post" }
      [:.form_row.edit-post_picture
        #?(:cljs
        { :on-click
          (fn [e]
            (.click (picture-input))
            (.preventDefault e)) })
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