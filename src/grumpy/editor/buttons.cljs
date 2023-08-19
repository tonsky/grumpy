(ns grumpy.editor.buttons
  (:require
    [clojure.string :as str]
    [grumpy.core.coll :as coll]
    [grumpy.core.fetch :as fetch]
    [grumpy.core.fragments :as fragments]
    [grumpy.core.macros :refer [oget oset! cond+]]
    [grumpy.core.transit :as transit]
    [grumpy.editor.state :as state]
    [rum.core :as rum]))


(defn to-publishing []
  (swap! state/*status assoc
    :error nil
    :status :publishing)
  (fetch/fetch! "POST" (oget js/location "pathname")
    {:body @state/*post
     
     :success
     (fn [payload]
       (let [post' (transit/read-string payload)]
         (oset! js/location "href" (str "/" (:post/id post')))))
     
     :error
     (fn [error]
       (let [error (str "Publising failed with " error)]
         (swap! state/*status assoc
           :post/status nil
           :post/error  error)))}))


(defn ready? []
  (and
    (nil? (fragments/subscribe state/*media-status :progress))
    (nil? (fragments/subscribe state/*media-status :error))
    (nil? (fragments/subscribe state/*status :status))
    (not (str/blank? (fragments/subscribe state/*post :post/body)))))


(rum/defc button-post < rum/reactive []
  (if (some? (fragments/subscribe state/*status :status))
    [:.post-post-loader.row.center.middle [:.loader.loading]]
    [:button.post-post.row
     {:disabled (not (ready?))
      :on-click (fn [_] (to-publishing))}
     [:img.button {:src "/static/editor/post_button.svg"}]
     [:img.hand {:src "/static/editor/post_hand.svg"}]
     [:.label "POST"]]))


(rum/defc button-update < rum/reactive []
  (if (some? (fragments/subscribe state/*status :status))
    [:.post-btn-loader [:.loader.small.loading] "Update"]
    [:button.btn.post-update
     {:disabled (not (ready?))
      :on-click (fn [_] (to-publishing))}
     "Update"]))


(rum/defc ui < rum/reactive []
  [:.column
   (when-some [error (fragments/subscribe state/*status :error)]
     [:.status {:style {:z-index 1}} error])
   (if (:post/id @state/*post)
     [:.row.right (button-update)]
     [:.row.right (button-post)])])
