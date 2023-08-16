(ns grumpy.editor.buttons
  (:require
   [clojure.string :as str]
   [grumpy.core.coll :as coll]
   [grumpy.core.fetch :as fetch]
   [grumpy.core.fragments :as fragments]
   [grumpy.core.macros :refer [oget oset! cond+]]
   [grumpy.core.transit :as transit]
   [rum.core :as rum]))


(defn to-publishing [*post]
  (swap! *post assoc
    :post/error nil
    :post/status :post.status/publishing)
  (let [post @*post]
    (fetch/fetch! "POST" (oget js/location "pathname")
      {:body post
       
       :success
       (fn [payload]
         (let [post' (transit/read-string payload)]
           (oset! js/location "href" (str "/post/" (:post/id post')))))
       
       :error
       (fn [error]
         (let [error (str "Publising failed with " error)]
           (swap! *post assoc
             :post/status nil
             :post/error  error)))})))


(defn ready? [*post]
  (and
    (= nil (fragments/subscribe *post :media/status))
    (not (str/blank? (fragments/subscribe *post :post/body)))))


(rum/defc button-post < rum/reactive [*post]
  (if (not= :post.status/publishing (fragments/subscribe *post :post/status))
    [:button.post-post.row
     {:disabled (not (ready? *post))
      :on-click (fn [_] (to-publishing *post))}
     [:img.button {:src "/static/editor/post_button.svg"}]
     [:img.hand {:src "/static/editor/post_hand.svg"}]
     [:.label "POST"]]
    [:.post-post-loader.row.center.middle [:.loader.loading]]))


(rum/defc button-update < rum/reactive [*post]
  (if (not= :post.status/publishing (fragments/subscribe *post :post/status))
    [:button.btn.post-update
     {:disabled (not (ready? *post))
      :on-click (fn [_] (to-publishing *post))}
     "Update"]
   [:.post-btn-loader [:.loader.small.loading] "Update"]))


(rum/defc ui < rum/reactive [*post]
  [:.column
   (when-some [error (fragments/subscribe *post :post/error)]
     [:.status {:style {:z-index 1}} error])
   (if (fragments/subscribe *post :post/id)
     [:.row.right (button-update *post)]
     [:.row.right (button-post *post)])])
