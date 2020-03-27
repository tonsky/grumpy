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
  (swap! *post coll/replace #"post/.*"
    :post/status :post.status/publishing)
  (let [post @*post]
    (fetch/post! (str "/draft/" (:id post) "/publish")
      {:body    (or (:body/edited post) (:body post))
       :success
       (fn [payload]
         (if (fragments/new? (:id post))
           (oset! js/location "href" "/")
           (let [post' (transit/read-transit-str payload)]
             (oset! js/location "href" (str "/post/" (:id post'))))))
       :error
       (fn [error]
         (let [error (str "Publising failed with " error)]
           (swap! *post coll/replace #"post/.*" :post/error error)))})))


(defn to-deleting [*post]
  (swap! *post coll/replace #"post/.*"
    :post/status :post.status/deleting)
  (let [post @*post]
    (fetch/post! (str "/draft/" (:id post) "/delete")
      {:success
       (fn [_]
         (if (fragments/new? (:id post))
           (oset! js/location "href" "/")
           (oset! js/location "href" (str "/post/" (:id post)))))
       :error
       (fn [error]
         (let [error (str "Cancel failed with " error)]
           (swap! *post coll/replace #"post/.*" :post/error error)))})))


(defn ready? [*post]
  (and
    (= nil (fragments/subscribe *post :media/status))
    (not 
      (str/blank?
        (or
          (fragments/subscribe *post :body/edited)
          (fragments/subscribe *post :body))))))


(rum/defc button-post < rum/reactive [*post]
  (if (not= :post.status/publishing (fragments/subscribe *post :post/status))
    [:button.post-post.row
     {:disabled (not (ready? *post))
      :on-click (fn [_] (to-publishing *post))}
     [:img.button {:src "/static/editor/post_button.svg"}]
     [:img.hand {:src "/static/editor/post_hand.svg"}]
     [:.label "POST"]]
    [:.post-post-loader.row.center.middle [:.loader.loading]]))


(rum/defc button-delete < rum/reactive [*post]
  (if (not= :post.status/deleting (fragments/subscribe *post :post/status))
    [:button.btn.self-middle.self-right
     {:on-click (fn [_] (to-deleting *post))}
     "Delete draft"]
    [:.post-btn-loader [:.loader.small.loading] "Delete draft"]))


(rum/defc button-update < rum/reactive [*post]
  (if (not= :post.status/publishing (fragments/subscribe *post :post/status))
    [:button.btn.post-update
     {:disabled (not (ready? *post))
      :on-click (fn [_] (to-publishing *post))}
     "Update"]
   [:.post-btn-loader [:.loader.small.loading] "Update"]))


(rum/defc button-cancel < rum/reactive [*post]
  (if (not= :post.status/deleting (fragments/subscribe *post :post/status))
    [:button.btn.self-right
     {:on-click (fn [_] (to-deleting *post))}
     "Cancel edit"]
    [:.post-btn-loader [:.loader.small.loading] "Cancel edit"]))


(rum/defc ui < rum/reactive [*post]
  [:.column
   (when-some [error (fragments/subscribe *post :post/error)]
     [:.status {:style {:z-index 1}} error])
   (if (fragments/new? (fragments/subscribe *post :id))
     [:.row.middle.space-between (button-delete *post) (button-post *post)]
     [:.row.middle.space-between (button-cancel *post) (button-update *post)])])