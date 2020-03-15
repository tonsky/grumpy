(ns ^:figwheel-hooks grumpy.editor.media
  (:require
   [cljs-drag-n-drop.core :as dnd]
   [grumpy.core.coll :as coll]
   [grumpy.core.fetch :as fetch]
   [grumpy.core.fragments :as fragments]
   [grumpy.core.macros :refer [oget oset! js-fn cond+]]
   [grumpy.core.transit :as transit]
   [rum.core :as rum]))


(defn to-upload-failed [*post message]
  (swap! *post coll/replace
    #":media/upload-.*"
    :media/status :media.status/upload-failed
    :media/failed-message message))


(defn to-no-media [*post]
  (swap! *post coll/dissoc-all #":picture|:picture-original|:media/.*"))


(defn to-delete-failed [*post message]
  (swap! *post coll/replace
    #":media/upload-.*"
    :media/status :media.status/delete-failed
    :media/failed-message message))


(defn to-deleting [*post]
  (swap! *post assoc
    :media/status :media.status/deleting)
  (fetch/post! (str "/draft/" (:id @*post) "/delete-media")
    {:success (fn [payload]
                (to-no-media *post))
     :error   (fn [message]
                (to-delete-failed *post message))}))


(defn to-displaying [*post]
  (swap! *post
    (fn [post]
      (js/URL.revokeObjectURL (:media/object-url post))
      (coll/dissoc-all post #":media/.*"))))


(defn to-downloading [*post updates]
  (let [post (swap! *post #(-> % 
                             (coll/replace
                               #"(:media/upload-.*|:media/failed-message)"
                               :media/status :media.status/downloading)
                             (merge updates)))
        img (js/Image.)]
    (oset! img "src" (str "/draft/" (:id post) "/" (:url (:picture post))))
    (.addEventListener img "load" #(to-displaying *post))
    (.addEventListener img "error" #(to-upload-failed *post "Failed to fetch img from server"))))


(defn to-uploading [*post file]
  (let [url     (js/URL.createObjectURL file)
        active? #(let [post @*post]
                   (and
                     (= url (:media/object-url post))
                     (= :media.status/uploading (:media/status post))))]
    (swap! *post assoc
      :media/object-url      url
      :media/mime-type       (oget file "type")
      :media/file            file
      :media/status          :media.status/uploading
      :media/upload-progress 0
      :media/upload-request
      (fetch/post! (str "/draft/" (:id @*post) "/upload-media")
        {:body     file
         :progress (fn [progress]
                     (when (active?)
                       (swap! *post assoc :media/upload-progress progress)))
         :success  (fn [payload]
                     (when (active?)
                       (to-downloading *post (transit/read-transit-str payload))))
         :error    (fn [message]
                     (when (active?)
                       (to-upload-failed *post message)))}))))

(defn to-uploading* [*post files]
  (when-some [file (when (> (alength files) 0)
                     (aget files 0))]
    (to-uploading *post file)))


;; Components


(rum/defc local-img < rum/reactive [*post]
  [:img {:src (fragments/subscribe *post :media/object-url)}])


(rum/defc remote-img < rum/reactive [*post]
  (let [id  (fragments/subscribe *post :id)
        url (fragments/subscribe-in *post [:picture :url])]
    [:img {:src (str "/draft/" id "/" url)}]))


(rum/defc dragging-impl
  < rum/reactive
    {:did-mount
     (fn [state]
       (let [[*post] (:rum/args state)]
         (dnd/subscribe! (rum/dom-node state) ::dragover
           {:enter (fn [_] (swap! *post assoc  :media/dragover? true))
            :drop  (fn [_ files] (to-uploading* *post files))
            :leave (fn [_] (swap! *post dissoc :media/dragover?))})
         state))}
  [*post]
  [:.dragging
   {:class (when (fragments/subscribe *post :media/dragover?) "dragover")}
   [:.label "Drop here"]])


(rum/defc dragging < rum/reactive [*post]
  (when (and
          (fragments/subscribe *post :media/dragging?)
          (contains?
            #{nil :media.status/upload-failed :media.status/deleting-failed}
            (fragments/subscribe *post :media/status)))
    (dragging-impl *post)))


(rum/defc file-input
  < rum/static
    {:did-mount
     (fn [state]
       (let [[*post] (:rum/args state)]
         (dnd/subscribe! js/document.documentElement ::dragging
           {:start (fn [_] (swap! *post assoc  :media/dragging? true))
            :end   (fn [_] (swap! *post dissoc :media/dragging?))})
         state))
     :will-unmount
     (fn [state]
       (dnd/unsubscribe! js/document.documentElement ::dragging)
       state)}
  [*post]
  [:input.no-display
   {:type      "file"
    :on-change #(let [files (-> % (oget "target") (oget "files"))]
                    (to-uploading* *post files))}])


(rum/defc no-media [*post]
  [:.upload.no-select.cursor-pointer
   {:on-click (fn [e]
                (-> (js/document.querySelector "input[type=file]") (.click))
                (.preventDefault e))}
   [:.corner.top-left]
   [:.corner.top-right]
   [:.corner.bottom-left]
   [:.corner.bottom-right]
   [:.label "Drag media here"]])


(rum/defc uploading < rum/reactive [*post]
  (let [percent (-> (fragments/subscribe *post :media/upload-progress) (* 100))]
    [:.media
     [:.media-wrap
      (local-img *post)
      [:.upload-overlay {:style {:height (str (- 100 percent) "%")}}]]
     [:.status "Uploading " (:media/object-url @*post) " / " (js/Math.floor percent) "%"]]))


(rum/defc upload-failed < rum/reactive [*post]
  [:.media
   [:.media-wrap
    (local-img *post)
    (when-not (fragments/subscribe *post :media/dragging?)
      [:.media-delete.cursor-pointer])
    [:.failed-overlay]]
   [:.status "Upload failed with " (fragments/subscribe *post :media/failed-message) " " 
    [:button.inline 
     {:on-click (fn [_] (to-uploading *post (:media/file @*post)))}
     "↻ Try again"]]])


(rum/defc downloading < rum/reactive [*post]
  [:.media
   [:.media-wrap (local-img *post)]
   [:.status "Downloading..."]])


(rum/defc displaying < rum/reactive [*post]
  [:.media
   [:.media-wrap
    (remote-img *post)
    (when-not (fragments/subscribe *post :media/dragging?)
      [:.media-delete.cursor-pointer
       {:on-click (fn [_] (to-deleting *post))}])]])


(rum/defc deleting < rum/reactive [*post]
  [:.media
   [:.media-wrap
    (remote-img *post)
    [:.deleting-overlay]]
   [:.status "Deleting..."]])


(rum/defc delete-failed < rum/reactive [*post]
  [:.media
   [:.media-wrap
    (remote-img *post)
    [:.failed-overlay]]
   [:.status "Delete failed with " (fragments/subscribe *post :media/failed-message) " "
    [:button.inline 
     {:on-click (fn [_] (to-deleting *post))}
      "↻ Try again"]]])


(rum/defc ui < rum/reactive [*post]
  (let [status (fragments/subscribe *post :media/status)]
    (list
      (file-input *post)
      (cond+
        (= :media.status/uploading status)
        (uploading *post)

        (= :media.status/downloading status)
        (downloading *post)

        (= :media.status/upload-failed status)
        (upload-failed *post)

        (= :media.status/deleting status)
        (deleting *post)

        (= :media.status/delete-failed status)
        (delete-failed *post)

        (some? (fragments/subscribe *post :picture))
        (displaying *post)

        :else
        (no-media *post)))))