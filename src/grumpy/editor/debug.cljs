(ns grumpy.editor.debug
  (:require
   [grumpy.core.coll :as coll]
   [grumpy.core.macros :refer [oget oset! cond+]]
   [rum.core :as rum]))


(def debug-forms
  [["New"                {}]
   ["Edit"               {:new? false :post {:id "0THGrh25y" :body "Livejournal mobile icons. My interpretation:\n\n- A guy was thinking about doing some writing. About heavy machinery, I suppose.\n- Nobody would publish his paper.\n- He brought two friends.\n- They wrote a book.\n- It gets published.\n- They receive a gift.\n- He starts thinking about next book, but one of the friends didn’t like it, so he left.\n- Some time later, second friend leaves too. He’s all alone again." :picture {:url "/post/0THGrh25y/M0C_mT1.fit.jpeg", :content-type "image/jpeg", :dimensions [750 265]}}}]
   ["Another author"     {:post {:author "freetonik"}}]
   ["Body changed"       {:post {:body "Hello, world!"} :body/edited "Hello, world! (edited)" :body/status :body.status/edited}]
   ["Body saving"        {:post {:body "Hello, world!"} :body/edited "Hello, world! (edited)" :body/status :body.status/saving}]
   ["Body failed"        {:post {:body "Hello, world!"} :body/edited "Hello, world! (edited)" :body/status :body.status/failed :body.status/message "Autosave failed"}]
   ["Dragging"           {:media/dragging? true}]
   ["Dragging over"      {:media/dragging? true :media/dragover? true}]
   ["Draging with image" {:media/dragging? true :post {:picture {:url "/post/0THGrh25y/M0C_mT1.orig.png"}}}]
   ["Draging over image" {:media/dragging? true :media/dragover? true :post {:picture {:url "/post/0THGrh25y/M0C_mT1.orig.png"}}}]
   ["Draging tall blob"  {:media/dragging? true :media/blob "/post/0T6kDVGD2/LycvDQV.orig.jpeg"}]
   ["Uploading 0%"       {:media/blob "/post/0THGrh25y/M0C_mT1.orig.png" :media/status :media.status/uploading :media.status/progress 0}]
   ["Uploading 50%"      {:media/blob "/post/0THGrh25y/M0C_mT1.orig.png" :media/status :media.status/uploading :media.status/progress 0.5}]
   ["Uploading tall 30%" {:media/blob "/post/0T6kDVGD2/LycvDQV.orig.jpeg" :media/status :media.status/uploading :media.status/progress 0.3}]
   ["Upload failed"      {:media/blob "/post/0THGrh25y/M0C_mT1.orig.png" :media/status :media.status/failed :media.status/message "500 Internal Server Error"}]
   ["Upload failed long" {:media/blob "/post/0THGrh25y/M0C_mT1.orig.png" :media/status :media.status/failed :media.status/message "500 Internal Server Error Machine says no No way Nope NaN NaN NaN"}]
   ["Uploaded"           {:post {:picture {:url "/post/0THGrh25y/M0C_mT1.fit.jpeg", :content-type "image/jpeg", :dimensions [750 265]}}}]
   ["Uploaded vertical"  {:post {:picture {:url "/post/0TDdxMGm8/M-Jw_2o.fit.jpeg", :content-type "image/jpeg", :dimensions [638 922]}}}]
])


(defonce *debug-form-key (atom "New"))


(defn switch-debug-form [delta]
  (let [idx      (->> debug-forms
                   (keep-indexed (fn [idx [k v]] (when (= k @*debug-form-key) idx)))
                   (first))
        idx'     (-> (+ idx delta)
                   (mod (count debug-forms)))
        [key' _] (get debug-forms idx')]
    (reset! *debug-form-key key')))


(rum/defc ui < rum/reactive [editor-ctor]
  [:.column
   [:.row
    [:select
     {:on-change (fn [e] (reset! *debug-form-key (-> e (oget "target") (oget "value"))))
      :value (rum/react *debug-form-key)}
     (for [[k v] debug-forms]
       [:option {:value k} k])]
    [:button.btn.secondary.small {:on-click (fn [_] (switch-debug-form -1))} "<"]
    [:button.btn.secondary.small {:on-click (fn [_] (switch-debug-form 1))} ">"]]
   (let [[_ form] (coll/seek (fn [[k v]] (= k (rum/react *debug-form-key))) debug-forms)
         form' (coll/deep-merge
                 {:new? true, :post {:body "" :author "nikitonsky"}}
                 form)]
     (editor-ctor (atom form')))])


(defn debug? []
  (re-find #"(\?|&|^)debug(&|$)" (or (oget js/location "search") "")))