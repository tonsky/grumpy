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
(defn fetch! [method url opts]
  (let [xhr     (js/XMLHttpRequest.)
        success (:success opts)
        error   (:error opts)]
    (.addEventListener xhr "load"
      (fn []
        (this-as resp
          (let [status (oget resp "status")
                body   (oget resp "responseText")]
            (if (not= status 200)
              (do
                (js/console.warn "Error fetching" url ":" body)
                (when (some? error)
                  (error body)))
              (success body))))))
    (when-some [progress (:progress opts)]
      (.addEventListener (oget xhr "upload") "progress"
        (fn [e]
          (when (some? (oget e "lengthComputable"))
            (progress (-> (oget e "loaded") (* 100) (/ (oget e "total")) js/Math.floor))))))
    (.open xhr method url)
    (.send xhr (:body opts)))))


#?(:cljs
(defn ^:export picture-input [] ;; ^:export == workaround for https://dev.clojure.org/jira/browse/CLJS-2410
  (js/document.querySelector "input[name=picture]")))


#?(:cljs
(defn update-preview! [files *post-local]
  (when-some [picture-url (:url (:picture @*post-local))]
    (when (str/starts-with? picture-url "blob:")
      (js/URL.revokeObjectURL picture-url)))
  (if-some [file (when (> (alength files) 0)
                   (aget files 0))]
    (swap! *post-local assoc :picture { :url (js/URL.createObjectURL file) })
    (swap! *post-local dissoc :picture))))


#?(:cljs
(defn upload! [post-id files *upload-status *post-saved]
  ;; TODO handle picture deletion
  (when-some [file (when (> (alength files) 0) (aget files 0))]
    (reset! *upload-status 0)
    (fetch! "POST" (str "/post/" post-id "/upload")
      { :body     file
        :progress (fn [percent]
                    (reset! *upload-status percent))
        :success  (fn [payload]
                    (reset! *upload-status ::uploaded)
                    (reset! *post-saved (:post (transit/read-transit-str payload))))
        :error    (fn [_]
                    (reset! *upload-status ::failed)) }))))


#?(:cljs
(defn save-post! [post-id post *post-saved]
  (let [saved   @*post-saved
        updates (into {}
                  (for [attr [:body :author]
                        :when (not= (get post attr) (get saved attr))]
                    [attr (get post attr)]))]
    (when-not (empty? updates)
      (fetch! "POST" (str "/post/" post-id "/save")
        { :body (transit/write-transit-str {:post updates})
          :success (fn [body]
                    (let [post (:post (transit/read-transit-str body))]
                      (reset! *post-saved post))) })))))


#?(:cljs
(defn publish! [post-id post]
  (fetch! "POST" (str "/post/" post-id "/publish")
    { :body    (transit/write-transit-str { :post (select-keys post [:body :author]) })
      :success (fn [payload]
                 (let [post (:post (transit/read-transit-str payload))]
                   (oset! js/location "href" (str "/post/" (:id post))))) })))


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
                     (oset! (picture-input) "files" files))
            :end   (fn [_] (js/document.body.classList.remove "dragover")) })
        state))
  
    :will-unmount
    (fn [state]
      (dnd/unsubscribe! js/document.documentElement ::editor)
      state) }))


(def handle-autosave
  #?(:clj {})
  #?(:cljs
  { :will-mount
    (fn [state]
      (let [{*post-local ::post-local
             *post-saved ::post-saved } state
            [{post-id :post-id}] (:rum/args state)
            cb #(save-post! post-id @*post-local *post-saved)]
        (assoc state ::autosave-timer (js/setInterval cb 1000)))) ;; FIXME
    :will-unmount
    (fn [state]
      (js/clearInterval (::autosave-timer state))
      (dissoc state ::autosave-timer)) }))


(rum/defcs editor
  < (local-init ::post-saved (fn [data] (:post data)))
    (local-init ::post-local (fn [data] (:post data)))
    (rum/local ::uploaded ::upload-status)
    handle-drag-n-drop
    handle-autosave

  [state data]

  (let [{ *post-local    ::post-local
          *post-saved    ::post-saved
          *upload-status ::upload-status } state
        {:keys [create? post-id user]} data
        post-local  @*post-local
        post-saved  @*post-saved
        picture-url (:url (:picture post-local))
        submit!     (js-fn [e]
                      (.preventDefault e)
                      (publish! post-id @*post-local))]
    [:form.edit-post
      { :on-submit submit! }
      [:.form_row.edit-post_picture
        { :class    (when (nil? picture-url) "edit-post_picture-empty")
          :on-click (js-fn [e]
                      (.click (picture-input))
                      (.preventDefault e)) }
        (when (some? picture-url)
          [:.edit-post_picture_inner
            [:img.post_img.edit-post_picture_img 
              { :src (if (str/starts-with? picture-url "blob:")
                       picture-url
                       (str "/draft/" post-id "/" picture-url)) }]
            (when (= ::failed @*upload-status)
              [:.edit-post_picture_failed])
            (when (number? @*upload-status)
              [:.edit-post_picture_progress { :style { :height (str (- 100 @*upload-status) "%")}}])])]
      [:input.edit-post_file
        { :type "file"
          :name "picture"
          :on-change (js-fn [e]
                       (let [files (-> e (oget "target") (oget "files"))]
                         (update-preview! files *post-local)
                         (upload! post-id files *upload-status *post-saved))) }]
      [:.form_row
        [:textarea
          { :value       (:body post-local)
            :on-key-down (js-fn [e]
                           (when (and (= 13 (oget e "keyCode"))
                                      (or (oget e "ctrlKey") (oget e "metaKey")))
                             (submit! e)))
            :on-change   #(swap! *post-local assoc :body (.-value (.-target %)))
            :name        "body"
            :placeholder "Be grumpy here..."
            :class       (when (not= (:body post-local) (:body post-saved))
                           "edit-post_body-dirty")
            :auto-focus  true }]]
      [:.form_row
        "Author: "
        [:input.edit-post_author
          { :type      "text"
            :name      "author"
            :value     (:author post-local)
            :on-change #(swap! *post-local assoc :author (.-value (.-target %)))
            :class     (when (not= (:author post-local) (:author post-saved))
                         "edit-post_author-dirty") }]]
      [:.form_row
        [:button { :type "submit" :on-click submit! }
          (if create? "Grumpost now!" "Update")]]]))


#?(:cljs
(defn ^:export refresh []
  (let [mount (js/document.querySelector ".mount")
        data  (-> (.getAttribute mount "data")
                  (edn/read-string))]
    (rum/mount (editor data) mount))))


(comment
  (require 'grumpy.editor :reload))