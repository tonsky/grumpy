(ns ^:figwheel-hooks grumpy.editor.media
  (:require
    [cljs-drag-n-drop.core :as dnd]
    [grumpy.core.fetch :as fetch]
    [grumpy.core.fragments :as fragments]
    [grumpy.core.macros :refer [oget oset! js-fn cond+]]
    [grumpy.core.mime :as mime]
    [grumpy.core.transit :as transit]
    [grumpy.editor.state :as state]
    [rum.core :as rum]))


(defn to-uploading [file object-url dimensions]
  (let [[width height] dimensions
        relevant? #(and
                     (= object-url (-> @state/*post :post/media :media/object-url))
                     (some? (:progress @state/*media-status)))]
    (when-some [object-url (-> @state/*post :post/media :media/object-url)]
      (js/URL.revokeObjectURL object-url))
    (reset! state/*media-status {:progress 0})
    (swap! state/*post assoc :post/media
      {:media/object-url   object-url
       :media/width        width
       :media/height       height
       :media/content-type (oget file "type")})
    (fetch/fetch! "POST" "/media/uploads"
      {:body file
       
       :progress
       (fn [progress]
         (when (relevant?)
           (swap! state/*media-status assoc
             :progress progress
             :message  (if (< progress 1)
                         (str "Uploading " (int (* 100 progress)) "%...")
                         "Converting..."))))

       :success
       (fn [payload]
         (when (relevant?)
           (reset! state/*media-status nil)
           (swap! state/*post
             (fn [post]
               (when-some [object-url (-> post :post/media :media/object-url)]
                 (js/URL.revokeObjectURL object-url))
               (merge post (transit/read-string payload))))))
       
       :error
       (fn [message]
         (when (relevant?)
           (reset! state/*media-status
             {:error (str "Upload failed with " message)})))})))


(defn to-measuring [file]
  (let [object-url (js/URL.createObjectURL file)
        video?     (mime/video? (oget file "type"))
        media      (js/document.createElement (if video? "video" "img"))]
    (.addEventListener media (if video? "loadedmetadata" "load")
      #(let [dimensions (if video?
                          [(.-videoWidth media) (.-videoHeight media)]
                          [(.-naturalWidth media) (.-naturalHeight media)])]
         (reset! state/*media-status nil)
         (reset! state/*media-drag nil)
         (to-uploading file object-url dimensions)))
    (.addEventListener media "error"
      (fn [_]
        (js/URL.revokeObjectURL object-url)
        (reset! state/*media-status {:error  "Unsupported format, we accept jpg/png/gif/mp4"})
        (reset! state/*media-drag nil)))
    (oset! media "src" object-url)))


(defn to-measuring* [files]
  (when-some [file (when (> (alength files) 0)
                     (aget files 0))]
    (to-measuring file)))


;; Components

(rum/defc render-dragging-impl < rum/reactive
  {:did-mount
   (fn [state]     
     (dnd/subscribe! (rum/dom-node state) ::dragover
       {:enter (fn [_]
                 (swap! state/*media-drag assoc :dragover? true))
        :drop  (fn [_ files]
                 (swap! state/*media-drag assoc :dropped? true)
                 (to-measuring* files))
        :leave (fn [_]
                 (when-not (:dropped? @state/*media-drag)
                   (swap! state/*media-drag assoc :dragover? false)))})
     state)}
  []
  [:.dragging
   {:class (when (fragments/subscribe state/*media-drag :dragover?) "dragover")}
   [:.label "Drop here"]])


(rum/defc render-dragging < rum/reactive []
  (when (and
          (nil? (fragments/subscribe state/*status :status))
          (fragments/subscribe state/*media-drag :dragging?)
          (nil? (fragments/subscribe state/*media-status :progress)))
    (render-dragging-impl)))


(rum/defc render-file-input
  < rum/static
  {:did-mount
   (fn [state]
     (dnd/subscribe! js/document.documentElement ::dragging
       {:start (fn [_]
                 (swap! state/*media-drag assoc :dragging? true))
        :end   (fn [_]
                 (when-not (:dropped? @state/*media-drag)
                   (swap! state/*media-drag assoc :dragging? false)))})
     state)
   :will-unmount
   (fn [state]
     (dnd/unsubscribe! js/document.documentElement ::dragging)
     state)}
  []
  [:input.no-display
   {:type      "file"
    :on-change #(let [files (-> % (oget "target") (oget "files"))]
                  (to-measuring* files))}])


(defn render-no-media []
  [:.upload.no-select.cursor-pointer
   {:on-click (fn [e]
                (when (nil? (:status @state/*status))
                  (-> (js/document.querySelector "input[type=file]") (.click)))
                (.preventDefault e))}
   [:.corner.top-left]
   [:.corner.top-right]
   [:.corner.bottom-left]
   [:.corner.bottom-right]
   [:.label "Drag media here"]
   (when-some [error (fragments/subscribe state/*media-status :error)]
     [:.status.stick-left.stick-bottom error])])


(rum/defc render-media-element [src content-type dimensions]
  (let [style (when-some [[w h] dimensions]
                (let [[w' h'] (fragments/fit w h 550 500)]
                  {:width w' :height h'}))]
    (if (mime/video? content-type)
      [:video
       {:auto-play    true
        :muted        true
        :loop         true
        :preload      "auto"
        :plays-inline true
        :style        style}
       [:source {:type content-type :src src}]]
      [:img {:src src
             :style style}])))


(rum/defc render-media < rum/reactive []
  (let [media        (fragments/subscribe state/*post :post/media)
        src          (or 
                       (:media/object-url media)
                       (some->> (:media/url media) (str "/media/")))
        content-type (:media/content-type media)
        dimensions   [(:media/width media) (:media/height media)]]
    (render-media-element src content-type dimensions)))


(rum/defc render-delete < rum/reactive []
  (when (and
          (not (fragments/subscribe state/*media-drag :dragging?))
          (nil? (fragments/subscribe state/*status :status)))
    [:.media-delete.cursor-pointer
     {:on-click (fn [_]
                  (swap! state/*post dissoc
                    :post/media
                    :post/media-full))}]))


(rum/defc render-overlay < rum/reactive []
  (cond+
    :let [progress (fragments/subscribe state/*media-status :progress)]
    
    (some? progress)
    (let [percent (* 100 progress)]
      [:.upload-overlay {:style {:height (str (- 100 percent) "%")}}])

    :let [error (fragments/subscribe state/*media-status :error)]
    
    (some? error)
    [:.failed-overlay]))


(rum/defc render-status < rum/reactive []
  (if-some [msg (fragments/subscribe state/*media-status :error)]
    [:.status.error.stick-left.stick-bottom msg]
    (when-some [msg (fragments/subscribe state/*media-status :message)]
      [:.status.stick-left.stick-bottom msg])))


(rum/defc ui < rum/reactive []
  (list
    (render-file-input)
    (if (some? (fragments/subscribe state/*post :post/media))
      [:.media
       [:.media-wrap
        (render-media)
        (render-delete)
        (render-overlay)]
       (render-status)]
      (render-no-media))))
