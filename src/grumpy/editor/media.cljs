(ns ^:figwheel-hooks grumpy.editor.media
  (:require
   [grumpy.core.fetch :as fetch]
   [grumpy.core.macros :refer [oget oset! js-fn cond+]]
   [grumpy.core.transit :as transit]
   [rum.core :as rum]))


(rum/defc no-media [dragged?]
  [:.upload.no-select.cursor-pointer
   [:.corner.top-left]
   [:.corner.top-right]
   [:.corner.bottom-left]
   [:.corner.bottom-right]
   [:.label (if dragged?
              "DROP IT!"
              "Drag media here")]])


(rum/defc media-uploading [{blob-url :blob/url
                            progress :picture.status/progress}]
  (let [percent (-> progress (* 100))]
    [:.media
     [:.media-wrap
      [:img {:src blob-url}]
      [:.upload-overlay {:style {:height (str (- 100 percent) "%")}}]]
     [:.status "> Uploading " (js/Math.floor percent) "%"]]))


(rum/defc media-failed [{blob-url :blob/url
                         message :picture.status/message}]
  [:.media
   [:.media-wrap
    [:img {:src blob-url}]
    [:.media-delete.cursor-pointer]
    [:.upload-overlay-failed]]
   [:.status "> Upload failed (" message ") " [:button.inline "↻ Try again"]]])


(rum/defc media-uploaded [picture]
  [:.media
   [:.media-wrap
    [:img {:src (:url picture)}]
    [:.media-delete.cursor-pointer]]])


(rum/defc ui
  < rum/reactive
    {:before-render
     (fn [state]
       (let [[form] (:rum/args state)
             {:keys [dragged?]} form
             set? (js/document.body.classList.contains "dragover")]
         (when (not= set? dragged?)
           (if dragged?
             (js/document.body.classList.add "dragover")
             (js/document.body.classList.remove "dragover"))))
       state)}
  [*form]
  (let [form (rum/react *form)
        {{picture :picture} :post
         dragged?           :dragged?
         picture-status     :picture/status} form]
    (cond+
      (some? picture)
      (media-uploaded picture)
      
      (= :picture.status/uploading picture-status)
      (media-uploading form)

      (= :picture.status/failed picture-status)
      (media-failed form)

      :else
      (no-media dragged?))))