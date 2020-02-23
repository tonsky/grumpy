(ns ^:figwheel-hooks grumpy.editor
  (:require
   [clojure.string :as str]
   [grumpy.core.coll :as coll]
   [grumpy.core.fragments :as fragments]
   [grumpy.core.macros :refer [oget oset! js-fn cond+]]
   [rum.core :as rum]))


(enable-console-print!)


(rum/defc no-media [dragged?]
  [:.upload.no-select.cursor-pointer
   [:.corner.top-left]
   [:.corner.top-right]
   [:.corner.bottom-left]
   [:.corner.bottom-right]
   [:.label (if dragged?
              "DROP IT!"
              "Drag media here")]])


(rum/defc media-uploaded [picture]
  [:.media
   [:.media-wrap
    [:img {:src (:url picture)}]
    [:.media-delete.cursor-pointer]]])


(rum/defc media-uploading [{blob-url :blob/url
                            progress :status.picture/progress}]
  (let [percent (-> progress (* 100))]
    [:.media
     [:.media-wrap
      [:img {:src blob-url}]
      [:.upload-overlay {:style {:height (str (- 100 percent) "%")}}]]
     [:.status "> Uploading " (js/Math.floor percent) "%"]]))


(rum/defc media-failed [{blob-url :blob/url
                         message :status.picture/message}]
  [:.media
   [:.media-wrap
    [:img {:src blob-url}]
    [:.media-delete.cursor-pointer]
    [:.upload-overlay-failed]]
   [:.status "> Upload failed (" message ") " [:button.inline "↻ Try again"]]])


(rum/defc media
  < {:before-render
     (fn [state]
       (let [[arg] (:rum/args state)
             {:keys [dragged?]} arg
             set? (js/document.body.classList.contains "dragover")]
         (when (not= set? dragged?)
           (if dragged?
             (js/document.body.classList.add "dragover")
             (js/document.body.classList.remove "dragover"))))
       state)}
  [arg]
  (let [{{picture :picture} :post
         dragged?           :dragged?
         status-picture     :status/picture} arg]
    (cond+
      (some? picture)
      (media-uploaded picture)
      
      (= :status.picture/uploading status-picture)
      (media-uploading arg)

      (= :status.picture/failed status-picture)
      (media-failed arg)

      :else
      (no-media dragged?))))


(rum/defc buttons-new [arg]
  [:.row.middle.space-between
   [:button.post-post.row
    [:img.button {:src "/static/editor/post_button.svg"}]
    [:img.hand {:src "/static/editor/post_hand.svg"}]
    [:.label "POST"]]
   [:button.post-delete.btn.self-middle.self-right "Delete draft"]])


(rum/defc buttons-edit [arg]
  [:.row.middle.space-between
   [:button.btn.post-update "Update"]
   [:button.post-cancel.btn.self-right "Cancel edit"]])


(rum/defc editor
  [arg]
  (let [{{:keys [id body author]} :post
         status-body              :status/body} arg]
    [:.editor.row
     [:img.post_avatar {:src (fragments/avatar-url author)}]
     
     [:.column.grow
      (media arg)

      (case status-body
        :status.body/changed
        [:div "Body: changed"]
        :status.body/saving
        [:div "Body: saving..."]
        :status.body/failed
        [:div "Body: failed (" (:status.body/message arg) ")"]
        nil)

      [:.textarea
       [:.input
        [:textarea {:placeholder "Be grumpy here..." :value body}]]
       [:.handle.column.center
        [:.rope]
        [:.ring.cursor-pointer]]]

      (if (nil? id)
        (buttons-new arg)
        (buttons-edit arg))]]))


(def debug-states
  [["New"                {}]
   ["Edit"               {:post {:id "0THGrh25y" :body "Livejournal mobile icons. My interpretation:\n\n- A guy was thinking about doing some writing. About heavy machinery, I suppose.\n- Nobody would publish his paper.\n- He brought two friends.\n- They wrote a book.\n- It gets published.\n- They receive a gift.\n- He starts thinking about next book, but one of the friends didn’t like it, so he left.\n- Some time later, second friend leaves too. He’s all alone again." :picture {:url "/post/0THGrh25y/M0C_mT1.fit.jpeg", :content-type "image/jpeg", :dimensions [750 265]}}}]
   ["Another author"     {:post {:author "freetonik"}}]
   ["Body changed"       {:post {:body "Hello, world!"} :status/body :status.body/changed}]
   ["Body saving"        {:post {:body "Hello, world!"} :status/body :status.body/saving}]
   ["Body failed"        {:post {:body "Hello, world!"} :status/body :status.body/failed :status.body/message "Autosave failed"}]
   ["Dragged"            {:dragged? true}]
   ["Dragged over image" {:dragged? true :post {:picture {:url "/post/0THGrh25y/M0C_mT1.orig.png"}}}]
   ["Uploading 0%"       {:blob/url "/post/0THGrh25y/M0C_mT1.orig.png" :status/picture :status.picture/uploading :status.picture/progress 0}]
   ["Uploading 50%"      {:blob/url "/post/0THGrh25y/M0C_mT1.orig.png" :status/picture :status.picture/uploading :status.picture/progress 0.5}]
   ["Uploading tall 30%" {:blob/url "/post/0T6kDVGD2/LycvDQV.orig.jpeg" :status/picture :status.picture/uploading :status.picture/progress 0.3}]
   ["Upload failed"      {:blob/url "/post/0THGrh25y/M0C_mT1.orig.png" :status/picture :status.picture/failed :status.picture/message "500 Internal Server Error"}]
   ["Upload failed long" {:blob/url "/post/0THGrh25y/M0C_mT1.orig.png" :status/picture :status.picture/failed :status.picture/message "500 Internal Server Error Machine says no No way Nope NaN NaN NaN"}]
   ["Uploaded"           {:post {:picture {:url "/post/0THGrh25y/M0C_mT1.fit.jpeg", :content-type "image/jpeg", :dimensions [750 265]}}}]
   ["Uploaded vertical"  {:post {:picture {:url "/post/0TDdxMGm8/M-Jw_2o.fit.jpeg", :content-type "image/jpeg", :dimensions [638 922]}}}]
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