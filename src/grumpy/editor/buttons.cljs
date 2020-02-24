(ns grumpy.editor.buttons
  (:require
   [grumpy.core.fetch :as fetch]
   [grumpy.core.fragments :as fragments]
   [grumpy.core.macros :refer [oget oset! cond+]]
   [grumpy.core.transit :as transit]
   [rum.core :as rum]))


;; FIXME "deleting" state

(defn to-deleting [*post]
  (let [post @*post]
    (fetch/fetch! "POST" (str "/draft/" (:id post) "/delete")
      {:success
       (fn [_]
         (if (fragments/new? (:id post))
           (oset! js/location "href" "/")
           (oset! js/location "href" (str "/post/" (:id post)))))})))


(rum/defc buttons-new [*post]
  [:.row.middle.space-between
   [:button.post-post.row
    [:img.button {:src "/static/editor/post_button.svg"}]
    [:img.hand {:src "/static/editor/post_hand.svg"}]
    [:.label "POST"]]
   [:button.post-delete.btn.self-middle.self-right
    {:on-click (fn [_] (to-deleting *post))}
    "Delete draft"]])


(rum/defc buttons-edit [*post]
  [:.row.middle.space-between
   [:button.btn.post-update "Update"]
   [:button.post-cancel.btn.self-right
    {:on-click (fn [_] (to-deleting *post))}
    "Cancel edit"]])


(rum/defc ui < rum/reactive [*post]
  (if (fragments/new? (rum/react (rum/cursor *post :id)))
    (buttons-new *post)
    (buttons-edit *post)))