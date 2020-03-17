(ns grumpy.editor.debug
  (:require
   [grumpy.core.coll :as coll]
   [grumpy.core.macros :refer [oget oset! cond+]]
   [rum.core :as rum]))


(def debug-posts
  [["New post"                {}]
   ["Ex. post"               {:id "0THGrh25y" :body "Livejournal mobile icons. My interpretation:\n\n- A guy was thinking about doing some writing. About heavy machinery, I suppose.\n- Nobody would publish his paper.\n- He brought two friends.\n- They wrote a book.\n- It gets published.\n- They receive a gift.\n- He starts thinking about next book, but one of the friends didn’t like it, so he left.\n- Some time later, second friend leaves too. He’s all alone again." :picture {:url "M0C_mT1.fit.jpeg", :content-type "image/jpeg", :dimensions [750 265]}}]
   ["New post another author"     {:author "freetonik" :id "@freetonik"}]
   ["Body changed"       {:body "Hello, world!" :body/edited "Hello, world! (edited)" :body/status :body.status/edited}]
   ["Body saving"        {:body "Hello, world!" :body/edited "Hello, world! (edited)" :body/status :body.status/saving}]
   ["Body failed"        {:body "Hello, world!" :body/edited "Hello, world! (edited)" :body/status :body.status/failed :body/error "Autosave failed"}]
   ["Media Dragging"           {:media/dragging? true}]
   ["Media Dragging over"      {:media/dragging? true :media/dragover? true}]
   ["Media Draging with image" {:id "0THGrh25y" :media/dragging? true :picture {:url "M0C_mT1.orig.png" :dimensions [750 265]}}]
   ["Media Draging over image" {:id "0THGrh25y" :media/dragging? true :media/dragover? true :picture {:url "M0C_mT1.orig.png" :dimensions [750 265]}}]
   ["Media Draging tall blob"  {:media/dragging? true :media/object-url "/post/0T6kDVGD2/LycvDQV.orig.jpeg" :media/dimensions [591 1280]}]
   ["Media Uploading 0%"       {:media/object-url "/post/0THGrh25y/M0C_mT1.orig.png" :media/dimensions [750 265] :media/status :media.status/uploading :media/upload-progress 0}]
   ["Media Uploading 50%"      {:media/object-url "/post/0THGrh25y/M0C_mT1.orig.png" :media/dimensions [750 265] :media/status :media.status/uploading :media/upload-progress 0.5}]
   ["Media Uploading tall 30%" {:media/object-url "/post/0T6kDVGD2/LycvDQV.orig.jpeg" :media/dimensions [591 1280] :media/status :media.status/uploading :media/upload-progress 0.3}]
   ["Media Upload failed"      {:media/object-url "/post/0THGrh25y/M0C_mT1.orig.png" :media/dimensions [750 265] :media/status :media.status/upload-failed :media/error "Upload failed with 500 Internal Server Error"}]
   ["Media Upload failed long" {:media/object-url "/post/0THGrh25y/M0C_mT1.orig.png" :media/dimensions [750 265] :media/status :media.status/upload-failed :media/error "Upload failed with 500 Internal Server Error Machine says no No way Nope NaN NaN NaN"}]
   ["Media Upload failed tall" {:media/object-url "/post/0T6kDVGD2/LycvDQV.orig.jpeg" :media/dimensions [591 1280] :media/status :media.status/upload-failed :media/error "Upload failed with 500 Internal Server Error"}]
   ["Media Displaying"          {:id "0THGrh25y" :picture {:url "M0C_mT1.fit.jpeg", :content-type "image/jpeg", :dimensions [750 265]}}]
   ["Media Displaying vertical" {:id "0TDdxMGm8" :picture {:url "M-Jw_2o.fit.jpeg", :content-type "image/jpeg", :dimensions [638 922]}}]
   ["Media Deleting"      {:media/status :media.status/deleting :id "0THGrh25y" :picture {:url "M0C_mT1.fit.jpeg", :content-type "image/jpeg", :dimensions [750 265]}}]
   ["Media Delete failed" {:media/status :media.status/delete-failed :media/error "Delete failed with 500 Internal Server Error" :id "0THGrh25y" :picture {:url "M0C_mT1.fit.jpeg", :content-type "image/jpeg", :dimensions [750 265]}}]
   ["New post publishing"        {:body "Posting this..." :post/status :post.status/publishing}]
   ["New post publishing failed" {:body "Posting this..." :post/error  "Posting failed with 500 Internal Server Error"}]
   ["New post deleting"          {:body "Posting this..." :post/status :post.status/deleting}]
   ["New post deleting failed"   {:body "Posting this..." :post/error  "Deleting failed with 500 Internal Server Error"}]
   ["Ex. post updating"          {:id "0THGrh25y" :body "Posting this..." :post/status :post.status/publishing :picture {:url "M0C_mT1.fit.jpeg", :content-type "image/jpeg", :dimensions [750 265]}}]
   ["Ex. post updating failed"   {:id "0THGrh25y" :body "Posting this..." :post/error  "Posting failed with 500 Internal Server Error" :picture {:url "M0C_mT1.fit.jpeg", :content-type "image/jpeg", :dimensions [750 265]}}]
   ["Ex. post cancelling"        {:id "0THGrh25y" :body "Posting this..." :post/status :post.status/deleting :picture {:url "M0C_mT1.fit.jpeg", :content-type "image/jpeg", :dimensions [750 265]}}]
   ["Ex. post cancelling failed" {:id "0THGrh25y" :body "Posting this..." :post/error  "Deleting failed with 500 Internal Server Error" :picture {:url "M0C_mT1.fit.jpeg", :content-type "image/jpeg", :dimensions [750 265]}}]
])


(defonce *debug-post-key (atom "New post"))


(defn switch-debug-post [delta]
  (let [idx      (->> debug-posts
                   (keep-indexed (fn [idx [k v]] (when (= k @*debug-post-key) idx)))
                   (first))
        idx'     (-> (+ idx delta)
                   (mod (count debug-posts)))
        [key' _] (get debug-posts idx')]
    (reset! *debug-post-key key')))


(rum/defc ui < rum/reactive [editor-ctor]
  [:.column
   [:.row
    [:select
     {:on-change (fn [e] (reset! *debug-post-key (-> e (oget "target") (oget "value"))))
      :value (rum/react *debug-post-key)}
     (for [[k v] debug-posts]
       [:option {:value k} k])]
    [:button.btn.secondary.small {:on-click (fn [_] (switch-debug-post -1))} "<"]
    [:button.btn.secondary.small {:on-click (fn [_] (switch-debug-post 1))} ">"]]
   (let [[_ post] (coll/seek (fn [[k v]] (= k (rum/react *debug-post-key))) debug-posts)
         post' (coll/deep-merge
                 {:body "" :author "nikitonsky" :id "@nikitonsky"}
                 post)]
     (editor-ctor (atom post')))])


(defn debug? []
  (re-find #"(\?|&|^)debug(&|$)" (or (oget js/location "search") "")))