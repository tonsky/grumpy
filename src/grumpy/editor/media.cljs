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
       (let [[*post] (:rum/args state)]
         (dnd/subscribe! (rum/dom-node state) ::dragover
           {:enter (fn [_] (swap! *post assoc  :media/dragover? true))
            :leave (fn [_] (swap! *post dissoc :media/dragover?))})
         state))}
  [*post]
  [:.dragging
   {:class (when (rum/react (rum/cursor *post :media/dragover?)) "dragover")}
   [:.label "Drop here"]])


(rum/defc dragging < rum/reactive [*post]
  (when (rum/react (rum/cursor *post :media/dragging?))
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


(rum/defc media-uploaded [post]
  [:.media
   [:.media-wrap
    [:img {:src (:url (:picture post))}]
    (when-not (:media/dragging? post)
      [:.media-delete.cursor-pointer])]])


(defn to-uploading [*post files]
  )

(rum/defc input
  < rum/static
    {:did-mount
     (fn [state]
       (let [[*post] (:rum/args state)]
         (dnd/subscribe! js/document.documentElement ::dragging
           {:start (fn [_] (swap! *post assoc  :media/dragging? true))
            :drop  (fn [_ files]
                     (when (:media/dragover? @*post)
                       (to-uploading *post files)))
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
                  (to-uploading *post files))}])


(rum/defc ui < rum/reactive
  [*post]
  (let [status (rum/react (rum/cursor *post :media/status))]
    (list
      (input *post)
      (cond+
        ;; TODO do not subscribe to the whole *post
        (= :media.status/uploading status)
        (media-uploading (rum/react *post))

        (= :media.status/failed status)
        (media-failed (rum/react *post))

        (some? (rum/react (rum/cursor *post :picture)))
        (media-uploaded (rum/react *post))

        :else
        (no-media)))))