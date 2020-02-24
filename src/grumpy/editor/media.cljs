(ns ^:figwheel-hooks grumpy.editor.media
  (:require
   [cljs-drag-n-drop.core :as dnd]
   [grumpy.core.fetch :as fetch]
   [grumpy.core.macros :refer [oget oset! js-fn cond+]]
   [grumpy.core.transit :as transit]
   [rum.core :as rum]))


(rum/defc dragging-impl
  < rum/reactive
    {:did-mount
     (fn [state]
       (let [[*form] (:rum/args state)]
         (dnd/subscribe! (rum/dom-node state) ::dragover
           {:enter (fn [_] (swap! *form assoc  :media/dragover? true))
            :leave (fn [_] (swap! *form dissoc :media/dragover?))})
         state))}
  [*form]
  [:.dragging
   {:class (when (rum/react (rum/cursor *form :media/dragover?)) "dragover")}
   [:.label "Drop here"]])


(rum/defc dragging < rum/reactive [*form]
  (when (rum/react (rum/cursor *form :media/dragging?))
    (dragging-impl *form)))


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


(rum/defc media-uploading [{blob-url :media/blob
                            progress :media.status/progress}]
  (let [percent (-> progress (* 100))]
    [:.media
     [:.media-wrap
      [:img {:src blob-url}]
      [:.upload-overlay {:style {:height (str (- 100 percent) "%")}}]]
     [:.status "> Uploading " (js/Math.floor percent) "%"]]))


(rum/defc media-failed [{blob-url  :media/blob
                         message   :media.status/message
                         dragging? :media/dragging?}]
  [:.media
   [:.media-wrap
    [:img {:src blob-url}]
    (when-not dragging?
      [:.media-delete.cursor-pointer])
    [:.upload-overlay-failed]]
   [:.status "> Upload failed (" message ") " [:button.inline "↻ Try again"]]])


(rum/defc media-uploaded [form]
  [:.media
   [:.media-wrap
    [:img {:src (:url (:picture (:post form)))}]
    (when-not (:media/dragging? form)
      [:.media-delete.cursor-pointer])]])


(defn to-uploading [*form files]
  )

(rum/defc input
  < rum/static
    {:did-mount
     (fn [state]
       (let [[*form] (:rum/args state)]
         (dnd/subscribe! js/document.documentElement ::dragging
           {:start (fn [_] (swap! *form assoc  :media/dragging? true))
            :drop  (fn [_ files] (to-uploading *form files))
            :end   (fn [_] (swap! *form dissoc :media/dragging?))})
         state))
     :will-unmount
     (fn [state]
       (dnd/unsubscribe! js/document.documentElement ::dragging)
       state)}
  [*form]
  [:input.media-input.no-display
   {:type      "file"
    :on-change #(let [files (-> % (oget "target") (oget "files"))]
                  (to-uploading *form files))}])


(rum/defc ui < rum/reactive
  [*form]
  (let [form (rum/react *form)
        {{picture :picture} :post
         status   :media/status} form]
    (list
      (input *form)
      (cond+
        (some? picture)
        (media-uploaded form)
        
        (= :media.status/uploading status)
        (media-uploading form)

        (= :media.status/failed status)
        (media-failed form)

        :else
        (no-media)))))