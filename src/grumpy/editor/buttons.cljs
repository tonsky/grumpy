(ns grumpy.editor.buttons
  (:require
   [grumpy.core.fetch :as fetch]
   [grumpy.core.macros :refer [oget oset! cond+]]
   [grumpy.core.transit :as transit]
   [rum.core :as rum]))


;; FIXME

(defn to-deleting [*form]
  (let [form @*form]
    (fetch/fetch! "POST" (str "/draft/" (:post-id form) "/delete")
      {:success
       (fn [_]
         (if (:new? form)
           (oset! js/location "href" "/")
           (oset! js/location "href" (str "/post/" (:post-id form)))))})))


(rum/defc buttons-new [*form]
  [:.row.middle.space-between
   [:button.post-post.row
    [:img.button {:src "/static/editor/post_button.svg"}]
    [:img.hand {:src "/static/editor/post_hand.svg"}]
    [:.label "POST"]]
   [:button.post-delete.btn.self-middle.self-right
    {:on-click (fn [_] (to-deleting *form))}
    "Delete draft"]])


(rum/defc buttons-edit [*form]
  [:.row.middle.space-between
   [:button.btn.post-update "Update"]
   [:button.post-cancel.btn.self-right
    {:on-click (fn [_] (to-deleting *form))}
    "Cancel edit"]])


(rum/defc ui < rum/reactive [*form]
  (if (rum/react (rum/cursor *form :new?))
    (buttons-new *form)
    (buttons-edit *form)))