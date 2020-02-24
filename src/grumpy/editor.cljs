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


(defonce *form (atom nil))


(rum/defc editor [*form]
  [:.editor.relative.row
   [:img.post_avatar {:src (fragments/avatar-url (:author (:post @*form)))}]
   [:.column.grow
    (editor.media/ui *form)
    (editor.body/ui *form)
    (editor.buttons/ui *form)]
   (editor.media/dragging *form)])


(defn ^:after-load ^:export refresh []
  (let [mount (js/document.querySelector ".mount")
        comp  (if (editor.debug/debug?)
                (editor.debug/ui editor)
                (do
                  (when (nil? @*form)
                    (reset! *form (-> (.getAttribute mount "data") (edn/read-string))))
                  (editor *form)))]
    (rum/mount comp mount)))