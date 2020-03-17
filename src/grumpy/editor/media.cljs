(ns ^:figwheel-hooks grumpy.editor.media
  (:require
   [cljs-drag-n-drop.core :as dnd]
   [grumpy.core.coll :as coll]
   [grumpy.core.fetch :as fetch]
   [grumpy.core.fragments :as fragments]
   [grumpy.core.macros :refer [oget oset! js-fn cond+]]
   [grumpy.core.mime :as mime]
   [grumpy.core.transit :as transit]
   [rum.core :as rum]))


(defn to-failed [*post message]
  (swap! *post assoc
    :media/status :media.status/failed
    :media/error message))


(defn to-deleting [*post]
  (let [post      (swap! *post assoc :media/status :media.status/deleting)
        relevant? #(= post @*post)]
    (fetch/post! (str "/draft/" (:id @*post) "/delete-media")
      {:success (fn [_]
                  (when (relevant?)
                    (when-some [object-url (:media/object-url @*post)]
                      (js/URL.revokeObjectURL object-url))
                    (swap! *post coll/dissoc-all #":picture|:picture-original|:media/.*")))
       :error   (fn [message]
                  (when (relevant?)
                    (to-failed *post (str "Delete failed with " message))))})))


(defn to-uploading [*post file object-url dimensions]
  (let [relevant? #(and
                     (= object-url (:media/object-url @*post))
                     (= :media.status/uploading (:media/status @*post)))]
    (when-some [object-url (:media/object-url @*post)]
      (js/URL.revokeObjectURL object-url))
    (swap! *post coll/replace #":media/.*"
      :media/object-url      object-url
      :media/dimensions      dimensions
      :media/mime-type       (oget file "type")
      :media/status          :media.status/uploading
      :media/upload-progress 0)
    (fetch/post! (str "/draft/" (:id @*post) "/upload-media")
      {:body     file
       :progress (fn [progress]
                   (when (relevant?)
                     (swap! *post assoc :media/upload-progress progress)))
       :success  (fn [payload]
                   (when (relevant?)
                     (swap! *post #(-> %
                                     (dissoc :media/status :media/upload-progress)
                                     (merge (transit/read-transit-str payload))))))
       :error    (fn [message]
                   (when (relevant?)
                     (swap! *post dissoc :media/upload-progress)
                     (to-failed *post (str "Upload failed with " message))))})))


(defn to-measuring [*post file]
  (let [object-url (js/URL.createObjectURL file)
        video?     (mime/video? (oget file "type"))
        media      (js/document.createElement (if video? "video" "img"))]
      (.addEventListener media (if video? "loadedmetadata" "load")
        #(let [dimensions (if video?
                            [(.-videoWidth media) (.-videoHeight media)]
                            [(.-naturalWidth media) (.-naturalHeight media)])]
           (swap! *post dissoc :media/error :media/dragging? :media/dragover? :media/dropped?)
           (to-uploading *post file object-url dimensions)))
      (.addEventListener media "error"
        (fn [_]
           (js/URL.revokeObjectURL object-url)
           (swap! *post #(-> %
                           (dissoc :media/dragging? :media/dragover? :media/dropped?)
                           (assoc :media/error "Unsupported format, we accept jpg/png/gif/mp4")))))
      (oset! media "src" object-url)))


(defn to-measuring* [*post files]
  (when-some [file (when (> (alength files) 0)
                     (aget files 0))]
    (to-measuring *post file)))


;; Components


(rum/defc render-dragging-impl
  < rum/reactive
    {:did-mount
     (fn [state]
       (let [[*post] (:rum/args state)]
         (dnd/subscribe! (rum/dom-node state) ::dragover
           {:enter (fn [_] (swap! *post assoc  :media/dragover? true))
            :drop  (fn [_ files]
                     (swap! *post assoc :media/dropped? true)
                     (to-measuring* *post files))
            :leave (fn [_]
                     (when-not (:media/dropped? @*post)
                       (swap! *post dissoc :media/dragover?)))})
         state))}
  [*post]
  [:.dragging
   {:class (when (fragments/subscribe *post :media/dragover?) "dragover")}
   [:.label "Drop here"]])


(rum/defc render-dragging < rum/reactive [*post]
  (when (and
          (nil? (fragments/subscribe *post :post/status))
          (fragments/subscribe *post :media/dragging?)
          (contains? #{nil :media.status/failed} (fragments/subscribe *post :media/status)))
    (render-dragging-impl *post)))


(rum/defc render-file-input
  < rum/static
    {:did-mount
     (fn [state]
       (let [[*post] (:rum/args state)]
         (dnd/subscribe! js/document.documentElement ::dragging
           {:start (fn [_] (swap! *post assoc  :media/dragging? true))
            :end   (fn [_]
                     (when-not (:media/dropped? @*post)
                       (swap! *post dissoc :media/dragging?)))})
         state))
     :will-unmount
     (fn [state]
       (dnd/unsubscribe! js/document.documentElement ::dragging)
       state)}
  [*post]
  [:input.no-display
   {:type      "file"
    :on-change #(let [files (-> % (oget "target") (oget "files"))]
                  (to-measuring* *post files))}])


(defn render-no-media [*post]
  [:.upload.no-select.cursor-pointer
   {:on-click (fn [e]
                (when (nil? (:post/status @*post))
                  (-> (js/document.querySelector "input[type=file]") (.click)))
                (.preventDefault e))}
   [:.corner.top-left]
   [:.corner.top-right]
   [:.corner.bottom-left]
   [:.corner.bottom-right]
   [:.label "Drag media here"]
   (when-some [msg (fragments/subscribe *post :media/error)]
     [:.status.stick-left.stick-bottom msg])])


(rum/defc render-media-element [src mime-type dimensions]
  (let [style (when-some [[w h] dimensions]
                (let [[w' h'] (fragments/fit w h 550 500)]
                  {:width w' :height h'}))]
    (if (mime/video? mime-type)
      [:video
       {:auto-play    true
        :muted        true
        :loop         true
        :preload      "auto"
        :plays-inline true
        :style        style}
       [:source {:type mime-type :src src}]]
      [:img {:src src
             :style style}])))

(rum/defc render-media < rum/reactive [*post]
  (if-some [src (fragments/subscribe *post :media/object-url)]
    (let [mime-type (fragments/subscribe *post :media/mime-type)
          dimensions (fragments/subscribe *post :media/dimensions)]
      (render-media-element src mime-type dimensions))
    (when-some [picture (fragments/subscribe *post :picture)]
      (let [id  (fragments/subscribe *post :id)
            src (str "/draft/" id "/" (:url picture))]
        (render-media-element src (:content-type picture) (:dimensions picture))))))


(rum/defc render-delete < rum/reactive [*post]
  (when (and
          (not (fragments/subscribe *post :media/dragging?))
          (nil? (fragments/subscribe *post :post/status)))
    [:.media-delete.cursor-pointer
     {:on-click (fn [_] (to-deleting *post))}]))


(rum/defc render-overlay < rum/reactive [*post]
  (case (fragments/subscribe *post :media/status)
    :media.status/uploading
    (let [percent (-> (fragments/subscribe *post :media/upload-progress) (* 100))]
      [:.upload-overlay {:style {:height (str (- 100 percent) "%")}}])

    :media.status/failed
    [:.failed-overlay]

    :media.status/deleting
    [:.deleting-overlay]

    nil))


(rum/defc render-status < rum/reactive [*post]
  (when-some [msg (fragments/subscribe *post :media/error)]
     [:.status.stick-left.stick-bottom msg]))


(rum/defc ui < rum/reactive
  [*post]
  (let [status (fragments/subscribe *post :media/status)]
    (list
      (render-file-input *post)
      (if (or (some? (fragments/subscribe *post :picture))
            (some? (fragments/subscribe *post :media/object-url)))
        [:.media
         [:.media-wrap
          (render-media *post)
          (render-delete *post)
          (render-overlay *post)]
         (render-status *post)]
        (render-no-media *post)))))