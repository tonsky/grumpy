(ns ^:figwheel-hooks
  grumpy.edit
  (:require
    [clojure.string :as str]
    [cljs.reader :as edn]
    [cljs-drag-n-drop.core :as dnd]
    [rum.core :as rum]
    [grumpy.transit :as transit]
    [grumpy.macros :refer [oget oset! js-fn]]))

(enable-console-print!)

(rum/defc editor []
  [:button.e2_post
    [:.e2_post_icon
      [:img.e2_post_icon_hand {:src "/static/edit/post_hand.svg"}]
      [:img.e2_post_icon_button {:src "/static/edit/post_button.svg"}]]
    [:.e2_post_label "POST"]])

(defn ^:after-load ^:export refresh []
  (let [mount (js/document.querySelector ".mount")]
    (rum/mount (editor) mount)))