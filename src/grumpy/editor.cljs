(ns ^:figwheel-hooks grumpy.editor
  (:require
   [cljs.reader :as edn]
   [clojure.string :as str]
   [grumpy.core.coll :as coll]
   [grumpy.core.fetch :as fetch]
   [grumpy.core.fragments :as fragments]
   [grumpy.core.macros :refer [oget oset! js-fn cond+]]
   [grumpy.core.transit :as transit]
   [grumpy.editor.body :as editor.body]
   [grumpy.editor.buttons :as editor.buttons]
   [grumpy.editor.debug :as editor.debug]
   [grumpy.editor.media :as editor.media]
   [rum.core :as rum]))


(enable-console-print!)


(defonce *post (atom nil))


(rum/defc avatar < rum/reactive
  [*avatar]
  [:img.post_avatar {:src (fragments/avatar-url (rum/react *avatar))}])


(rum/defc editor [*post]
  [:.editor.relative.row
   (avatar (rum/cursor *post :author))
   [:.column.grow
    (editor.media/ui *post)
    (editor.body/ui *post)
    (editor.buttons/ui *post)]
   (editor.media/dragging *post)])


(defn ^:after-load ^:export refresh []
  (let [mount (js/document.querySelector ".mount")
        comp  (if (editor.debug/debug?)
                (editor.debug/ui editor)
                (do
                  (when (nil? @*post)
                    (reset! *post (-> (.getAttribute mount "data") (edn/read-string))))
                  (editor *post)))]
    (rum/mount comp mount)))