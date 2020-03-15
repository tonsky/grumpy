(ns ^:figwheel-hooks grumpy.editor.media
  (:require
   [cljs-drag-n-drop.core :as dnd]
   [grumpy.core.coll :as coll]
   [grumpy.core.fetch :as fetch]
   [grumpy.core.macros :refer [oget oset! js-fn cond+]]
   [grumpy.core.transit :as transit]
   [rum.core :as rum]))


(defn to-deleting [*post])


(defn to-failed [*post message]
  (swap! *post coll/replace
    #":media/upload-.*"
    :media/status :media.status/failed
    :media/failed-message message))


(defn to-displaying [*post]
  (swap! *post
    (fn [post]
      (js/URL.revokeObjectURL (:media/object-url post))
      (coll/replace post #":media/.*"))))


(defn to-downloading [*post updates]
  (let [post (swap! *post #(-> % 
                             (coll/replace
                               #"(:media/upload-.*|:media/failed-message)"
                               :media/status :media.status/downloading)
                             (merge updates)))
        img (js/Image.)]
    (oset! img "src" (str "/draft/" (:id post) "/" (:url (:picture post))))
    (.addEventListener img "load" #(to-displaying *post))
    (.addEventListener img "error" #(to-failed *post "Failed to fetch img from server"))))


(defn to-uploading [*post file]
  (to-deleting *post) ;; make sure current state is cleaned up
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
                       (to-failed *post message)))}))))

(defn to-uploading* [*post files]
  (when-some [file (when (> (alength files) 0)
                     (aget files 0))]
    (to-uploading *post file)))


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
   {:class (when (rum/react (rum/cursor *post :media/dragover?)) "dragover")}
   [:.label "Drop here"]])


(rum/defc dragging < rum/reactive [*post]
  (when (and (rum/react (rum/cursor *post :media/dragging?))
          (not (contains?
                 #{:media.status/uploading :media.status/deleting}
                 (rum/react (rum/cursor *post :media/status)))))
    (dragging-impl *post)))


(rum/defc no-media []
  [:.upload.no-select.cursor-pointer
   {:on-click (fn [e]
                (-> (js/document.querySelector ".media-input") (.click))
                (.preventDefault e))}
   [:.corner.top-left]
   [:.corner.top-right]
   [:.corner.bottom-left]
   [:.corner.bottom-right]
   [:.label "Drag media here"]])


(rum/defc uploading [{object-url :media/object-url
                      progress   :media/upload-progress}]
  (let [percent (-> progress (* 100))]
    [:.media
     [:.media-wrap
      [:img {:src object-url}]
      [:.upload-overlay {:style {:height (str (- 100 percent) "%")}}]]
     [:.status "Uploading " (js/Math.floor percent) "%"]]))


(rum/defc failed < rum/reactive [*post]
  (let [object-url (rum/react (rum/cursor *post :media/object-url))
        message    (rum/react (rum/cursor *post :media/failed-message))
        dragging?  (rum/react (rum/cursor *post :media/dragging?))]
    [:.media
     [:.media-wrap
      [:img {:src object-url}]
      (when-not dragging?
        [:.media-delete.cursor-pointer])
      [:.upload-overlay-failed]]
     [:.status "Upload failed with " message " " 
      [:button.inline 
       {:on-click (fn [_]
                    (to-uploading *post (:media/file @*post)))}
       "↻ Try again"]]]))


(rum/defc downloading [post]
  [:.media
   [:.media-wrap
    [:img {:src (:media/object-url post)}]]
   [:.status "Downloading..."]])


(rum/defc displaying [post]
  [:.media
   [:.media-wrap
    [:img {:src (str "/draft/" (:id post) "/" (:url (:picture post)))}]
    (when-not (:media/dragging? post)
      [:.media-delete.cursor-pointer])]])


(rum/defc deleting [post]
  [:.media
   [:.media-wrap
    [:img {:src (str "/draft/" (:id post) "/" (:url (:picture post)))}]
    [:.media-deleting]]])


(rum/defc input
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
  [:input.media-input.no-display
   {:type      "file"
    :on-change #(let [files (-> % (oget "target") (oget "files"))]
                    (to-uploading* *post files))}])


(rum/defc ui < rum/reactive
  [*post]
  (let [status (rum/react (rum/cursor *post :media/status))]
    (list
      (input *post)
      (cond+
        ;; TODO deleting state
        ;; TODO do not subscribe to the whole *post
        (= :media.status/uploading status)
        (uploading (rum/react *post))

        (= :media.status/downloading status)
        (downloading (rum/react *post))

        (= :media.status/failed status)
        (failed *post)

        (some? (rum/react (rum/cursor *post :picture)))
        (displaying (rum/react *post))

        :else
        (no-media)))))