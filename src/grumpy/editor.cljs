(ns ^:figwheel-hooks grumpy.editor
  (:require
    [cljs.reader :as edn]
    [grumpy.core.fragments :as fragments]
    [grumpy.editor.body :as editor.body]
    [grumpy.editor.buttons :as editor.buttons]
    [grumpy.editor.media :as editor.media]
    [grumpy.editor.state :as state]
    [rum.core :as rum]))


(enable-console-print!)


(rum/defc avatar < rum/reactive []
  [:img.post_avatar
   {:src (fragments/avatar-url (:post/author @state/*post))}])


(rum/defc editor []
  [:.editor.relative.row
   (avatar)
   [:.column.grow
    (editor.media/ui)
    (editor.body/ui)
    (editor.buttons/ui)]
   (editor.media/render-dragging)])


(defn ^:after-load ^:export refresh []
  (let [mount (js/document.querySelector ".mount")
        comp  (do
                (when (nil? @state/*post)
                  (reset! state/*post (-> (.getAttribute mount "data") (edn/read-string))))
                (editor state/*post))]
    (rum/mount comp mount)))
