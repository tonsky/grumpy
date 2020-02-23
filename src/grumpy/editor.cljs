(ns ^:figwheel-hooks grumpy.editor
  (:require
   [clojure.string :as str]
   [grumpy.core.coll :as coll]
   [grumpy.core.fragments :as fragments]
   [grumpy.core.macros :refer [oget oset! js-fn]]
   [rum.core :as rum]))


(enable-console-print!)


(rum/defc editor [state]
  (let [{:keys [post]} state
        {:keys [id body author picture]} post]
    [:.editor.grid
     [:img.post_avatar {:src (fragments/avatar-url author)}]
     
     [:.editing-col.self-hstretch.column
      (if (some? picture)
        [:.post_img.post_img-flex
         [:img {:src (:url picture)}]
         [:.media-delete.cursor-pointer]
         [:.status "> Saved"]]
        [:.upload.no-select.cursor-pointer
         [:.corner.top-left]
         [:.corner.top-right]
         [:.corner.bottom-left]
         [:.corner.bottom-right]
         [:.label "Drag media here"]])

      [:.textarea
       [:.input
        [:textarea {:placeholder "Be grumpy here..." :value body}]]
       [:.handle.column.center
        [:.rope]
        [:.ring.cursor-pointer]]]]

     (if (nil? id)
       [:button.post-post.row
        [:img.button {:src "/static/editor/post_button.svg"}]
        [:img.hand {:src "/static/editor/post_hand.svg"}]
        [:.label "POST"]]
       [:button.btn.post-update "Update"])

     (if (nil? id)
       [:button.post-delete.btn.self-middle.self-right "Delete draft"]
       [:button.post-cancel.btn.self-right "Cancel edit"])]))


(def debug-states
  [["New"              {}]
   ["Edit"             {:post {:id "0THGrh25y" :body "Livejournal mobile icons. My interpretation:\n\n- A guy was thinking about doing some writing. About heavy machinery, I suppose.\n- Nobody would publish his paper.\n- He brought two friends.\n- They wrote a book.\n- It gets published.\n- They receive a gift.\n- He starts thinking about next book, but one of the friends didn’t like it, so he left.\n- Some time later, second friend leaves too. He’s all alone again." :picture {:url "/post/0THGrh25y/M0C_mT1.fit.jpeg", :content-type "image/jpeg", :dimensions [750 265]}}}]
   ["Another author"   {:post {:author "freetonik"}}]
   ["Body changed"     {:post {:body "Hello, world!"} :status/body :status.body/changed}]
   ["Body saving"      {:post {:body "Hello, world!"} :status/body :status.body/saving}]
   ["Body failed"      {:post {:body "Hello, world!"} :status/body :status.body/failed :status.body/message "Autosave failed"}]
   ["Dragged"          {:dragged? true}]
   ["Dragged over image" {:dragged? true :post {:picture {:url "/post/0THGrh25y/M0C_mT1.orig.png"}}}]
   ["Uploading 0%"       {:blob/url "/post/0THGrh25y/M0C_mT1.orig.png" :status/picture :status.picture/uploading :status.picture/progress 0}]
   ["Uploading 50%"      {:blob/url "/post/0THGrh25y/M0C_mT1.orig.png" :status/picture :status.picture/uploading :status.picture/progress 0.5}]
   ["Uploaded"         {:post {:picture {:url "/post/0THGrh25y/M0C_mT1.fit.jpeg", :content-type "image/jpeg", :dimensions [750 265]}}}]
   ["Uploaded vertical" {:post {:picture {:url "/post/0TDdxMGm8/M-Jw_2o.fit.jpeg", :content-type "image/jpeg", :dimensions [638 922]}}}]
   ["Upload failed"    {:blob/url "/post/0THGrh25y/M0C_mT1.orig.png" :status/picture :status.picture/failed :status.picture/message "500 Internal Server Error"}]
   ])


(defonce *debug-state-key (atom "New"))


(defn switch-debug-state [delta]
  (let [idx      (->> debug-states
                   (keep-indexed (fn [idx [k v]] (when (= k @*debug-state-key) idx)))
                   (first))
        idx'     (-> (+ idx delta)
                   (mod (count debug-states)))
        [key' _] (get debug-states idx')]
    (reset! *debug-state-key key')))


(rum/defc debug < rum/reactive []
  [:.column
   [:.row
    [:select
     {:on-change (fn [e] (reset! *debug-state-key (-> e (oget "target") (oget "value"))))
      :value (rum/react *debug-state-key)}
     (for [[k v] debug-states]
       [:option {:value k} k])]
    [:button.btn.secondary.small {:on-click (fn [_] (switch-debug-state -1))} "<"]
    [:button.btn.secondary.small {:on-click (fn [_] (switch-debug-state 1))} ">"]]
   (let [[_ state] (coll/seek (fn [[k v]] (= k (rum/react *debug-state-key))) debug-states)
         state' (merge-with merge
                  {:post {:body "" :author "nikitonsky"}}
                  state)]
     (editor state'))])


(defn ^:after-load ^:export refresh []
  (let [mount (js/document.querySelector ".mount")
        debug? (re-find #"(\?|&|^)debug(&|$)" (or (oget js/location "search") ""))]
    (rum/mount (if debug? (debug) (editor {})) mount)))