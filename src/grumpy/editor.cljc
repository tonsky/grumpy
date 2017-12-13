(ns grumpy.editor
  (:require
    [clojure.string :as str]
    #?(:clj  [clojure.edn :as edn]
       :cljs [cljs.reader :as edn])
    #?(:cljs [cljs-drag-n-drop.core :as dnd])
    [rum.core :as rum]
    [grumpy.transit :as transit]
    [grumpy.macros :refer [oget oset! js-fn]]))


#?(:cljs (enable-console-print!))


#?(:cljs
(defn ^:export picture-input [] ;; ^:export == workaround for https://dev.clojure.org/jira/browse/CLJS-2410
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


(defn local-init [key init-fn]
  { :will-mount
    #?(:clj
    (fn [state]
      (assoc state key (atom (apply init-fn (:rum/args state))))))
    #?(:cljs
    (fn [state]
      (let [local-state (atom (apply init-fn (:rum/args state)))
            component   (:rum/react-component state)]
        (add-watch local-state key
          (fn [_ _ _ _]
            (rum/request-render component)))
        (assoc state key local-state)))) })


(def handle-drag-n-drop
  #?(:clj {})
  #?(:cljs
  { :will-mount
    (fn [state]
      (let [*picture-url (::picture-url state)]
        (dnd/subscribe! js/document.documentElement ::editor
          { :start (fn [_] (js/document.body.classList.add "dragover"))
            :drop  (fn [e files]
                     (update-preview! files *picture-url)
                     (oset! (picture-input) "files" files))
            :end   (fn [_] (js/document.body.classList.remove "dragover")) })
        state))
  
    :will-unmount
    (fn [state]
      (dnd/unsubscribe! js/document.documentElement ::editor)
      state) }))


#?(:cljs
(defn fetch! [method url payload cb]
  (let [xhr (js/XMLHttpRequest.)]
    (.addEventListener xhr "load"
      (fn []
        (this-as resp
          (let [status (oget resp "status")
                body   (oget resp "responseText")]
            (if (not= status 200)
              (js/console.warn "Error fetching" url ":" body)
              (cb body))))))
    (.open xhr method url)
    (.send xhr payload))))


#?(:cljs
(defn save! [post-id post *post-saved]
  (fetch! "POST" (str "/post/" post-id "/save")
    (transit/write-transit-str {:post post}) 
    (fn [body]
      (let [post (:post (transit/read-transit-str body))]
        (reset! *post-saved post))))))


(def handle-autosave
  #?(:clj {})
  #?(:cljs
  { :will-mount
    (fn [state]
      (let [{*post-local ::post-local
             *post-saved ::post-saved } state
            [{post-id :post-id}] (:rum/args state)
            cb #(when (not= (select-keys @*post-local [:body :author])
                            (select-keys @*post-saved [:body :author]))
                  (save! post-id @*post-local *post-saved))]
        (assoc state ::autosave-timer (js/setInterval cb 1000)))) ;; FIXME
    :will-unmount
    (fn [state]
      (js/clearInterval (::autosave-timer state))
      (dissoc state ::autosave-timer)) }))


(rum/defcs editor
  < (local-init ::picture-url
      (fn [{post :post}]
        (when-some [pic (:picture post)]
          (str "/post/" (:id post) "/" (:url pic)))))
    (local-init ::post-saved (fn [data] (:post data)))
    (local-init ::post-local (fn [data] (:post data)))
    handle-drag-n-drop
    handle-autosave

  [state data]

  (let [{ *picture-url ::picture-url
          *post-local  ::post-local
          *post-saved  ::post-saved } state
        {:keys [create? post-id user]} data
        dirty? (not= @*post-local @*post-saved)]
    [:form.edit-post
      { :action   (str "/post/" post-id "/edit")
        :enc-type "multipart/form-data"
        :method   "post" }
      [:.form_row.edit-post_picture
        { :class    (when (nil? @*picture-url) "edit-post_picture-empty")
          :on-click (js-fn [e]
                      (.click (picture-input))
                      (.preventDefault e)) }
        (when-some [picture-url @*picture-url]
          [:img.post_img.edit-post_picture_img { :src picture-url }])]
      [:input.edit-post_file
        { :type "file"
          :name "picture"
          :on-change (js-fn [e]
                       (let [files (-> e (oget "target") (oget "files"))]
                         (update-preview! files *picture-url))) }]
      [:.form_row
        [:textarea
          { :value       (:body @*post-local)
            :on-change   #(swap! *post-local assoc :body (.-value (.-target %)))
            :name        "body"
            :placeholder "Be grumpy here..."
            :class       (when (not= (:body @*post-local) (:body @*post-saved))
                           "edit-post_body-dirty")
            :auto-focus  true }]]
      [:.form_row
        "Author: "
        [:input.edit-post_author
          { :type      "text"
            :name      "author"
            :value     (:author @*post-local)
            :on-change #(swap! *post-local assoc :author (.-value (.-target %)))
            :class     (when (not= (:author @*post-local) (:author @*post-saved))
                         "edit-post_author-dirty") }]]
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