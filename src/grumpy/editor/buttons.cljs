(ns grumpy.editor.buttons
  (:require
   [grumpy.core.fetch :as fetch]
   [grumpy.core.macros :refer [oget oset! js-fn cond+]]
   [grumpy.core.transit :as transit]
   [rum.core :as rum]))


(rum/defc buttons-new [form]
  [:.row.middle.space-between
   [:button.post-post.row
    [:img.button {:src "/static/editor/post_button.svg"}]
    [:img.hand {:src "/static/editor/post_hand.svg"}]
    [:.label "POST"]]
   [:button.post-delete.btn.self-middle.self-right "Delete draft"]])


(rum/defc buttons-edit [form]
  [:.row.middle.space-between
   [:button.btn.post-update "Update"]
   [:button.post-cancel.btn.self-right "Cancel edit"]])


(rum/defc ui < rum/reactive [*form]
  (let [form (rum/react *form)]
    (if (:new? form)
      (buttons-new form)
      (buttons-edit form))))