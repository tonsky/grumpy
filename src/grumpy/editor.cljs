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
  [:.row
   [:img.post_avatar {:src "/static/nikitonsky.jpg"}]
   [:.column.grow
    [:.upload.no-select.cursor-pointer
     [:.corner.top-left]
     [:.corner.top-right]
     [:.corner.bottom-left]
     [:.corner.bottom-right]
     [:.label "Drag media here"]]
    [:textarea.post_body {:placeholder "Be grumpy here..."}]

    [:.grid.middle {:style {:grid-template-columns "auto auto 10px calc(50% - 25px)"}}
     [:.col1.cursor-default "Author"]
     [:input.col2 {:type "text" :value "nikitonsky"}]
     [:.col3.self-top.column.center
      [:.ta-handle_rope]
      [:.ta-handle_ring.cursor-pointer]]
     [:.label.col1 "Tags"]
     [:input.col2-4 {:type "text" :value "Windows Desktop HiDPI"}]
     [:.label.col1 "Status"]
     [:.status.col2-4.row.cursor-default
      [:.icon]
      [:.label "Saved"]]]

    [:.row.middle
     [:button.btn-post-post.row
      [:img.button {:src "/static/editor/post_button.svg"}]
      [:img.hand {:src "/static/editor/post_hand.svg"}]
      [:.label "POST"]]
     [:button.btn.tertiary "Delete draft"]]]])

(defn ^:after-load ^:export refresh []
  (let [mount (js/document.querySelector ".mount")]
    (rum/mount (editor) mount)))