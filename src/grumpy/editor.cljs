(ns ^:figwheel-hooks grumpy.editor
  (:require
   [clojure.string :as str]
   [cljs.reader :as edn]
   [cljs-drag-n-drop.core :as dnd]
   [rum.core :as rum]
   [grumpy.transit :as transit]
   [grumpy.macros :refer [oget oset! js-fn]]))


(enable-console-print!)


(def states
  {"New" {:post {:body "" :author "nikitonsky"}}
   "Another author"   {:post {:body "" :author "freetonik"}}
   "Anonymous author" {:post {:body "" :author "abc"}}
   "Edited" {:post {:body "Hello, world!" :author "nikitonsky"}}
   "Dragged" {}
   "Sending" {}})


(rum/defc editor [{:keys []}]
  [:.editor.grid
   [:img.post_avatar {:src "/static/nikitonsky.jpg"}]

   [:.editing-col.self-hstretch.column
    [:.upload.no-select.cursor-pointer
     [:.corner.top-left]
     [:.corner.top-right]
     [:.corner.bottom-left]
     [:.corner.bottom-right]
     [:.label "Drag media here"]
     [:.media-delete]]

    [:.textarea
     [:textarea {:placeholder "Be grumpy here..."}]
     [:.handle.column.center
      [:.rope]
      [:.ring.cursor-pointer]]]]

   [:.extra-col.self-hstretch.grid.middle
    [:.author-label.cursor-default "Author"]
    [:input.author-input {:type "text" :value "nikitonsky"}]

    [:.tags-label.cursor-default "Tags"]
    [:input.tags-input {:type "text" :value "#Windows #Desktop #HiDPI"}]

    [:.status-label "Status"]
    [:.status.row.cursor-default
     [:.icon]
     [:.label "Saved"]]]

   [:button.post-post.row
    [:img.button {:src "/static/editor/post_button.svg"}]
    [:img.hand {:src "/static/editor/post_hand.svg"}]
    [:.label "POST"]]

   [:button.post-delete.self-middle.btn.secondary "Delete draft"]])


(defn ^:after-load ^:export refresh []
  (let [mount (js/document.querySelector ".mount")]
    (rum/mount (editor) mount)))