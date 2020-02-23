(ns ^:figwheel-hooks grumpy.editor
  (:require
   [cljs.reader :as edn]
   [clojure.string :as str]
   [grumpy.core.coll :as coll]
   [grumpy.core.fetch :as fetch]
   [grumpy.core.fragments :as fragments]
   [grumpy.core.macros :refer [oget oset! js-fn cond+]]
   [grumpy.core.transit :as transit]
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
                            progress :picture.status/progress}]
  (let [percent (-> progress (* 100))]
    [:.media
     [:.media-wrap
      [:img {:src blob-url}]
      [:.upload-overlay {:style {:height (str (- 100 percent) "%")}}]]
     [:.status "> Uploading " (js/Math.floor percent) "%"]]))


(rum/defc media-failed [{blob-url :blob/url
                         message :picture.status/message}]
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
         picture-status     :picture/status} arg]
    (cond+
      (some? picture)
      (media-uploaded picture)
      
      (= :picture.status/uploading picture-status)
      (media-uploading arg)

      (= :picture.status/failed picture-status)
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


(defonce *form (atom nil))
(declare on-body-saving!)


(defn schedule-saving! []
  (when (nil? (:body/timer @*form))
    (swap! *form assoc :body/timer (js/setTimeout on-body-saving! 5000))))


(defn on-body-saving! []
  (let [form @*form
        edited (:body/edited form)]
    (when (not= edited (:body (:post form)))
      (swap! *form #(-> %
                      (dissoc :body/timer)
                      (assoc :body/status :body.status/saving)))
      (fetch/fetch! "POST" (str "/post/" (:post-id form) "/update-body")
        {:body    (transit/write-transit-str {:post {:body edited}})
         :success (fn [payload]
                    (when (= (:body/edited @*form) edited)
                      (swap! *form #(-> %
                                      (dissoc :body/edited)
                                      (dissoc :body/status)
                                      (dissoc :body.status/message)
                                      (assoc-in [:post :body] edited)))))
          :error  (fn [payload]
                    (swap! *form assoc
                      :body/status :body.status/failed
                      :body.status/message payload)
                    (schedule-saving!))}))))


(defn on-body-edited! [value]
  (swap! *form #(-> %
                  (dissoc :body.status/message)
                  (assoc :body/edited value)
                  (assoc :body/status :body.status/edited))
  (schedule-saving!)))


(rum/defc editor < rum/reactive [*form]
  (let [{{:keys [body author]} :post
         new?        :new?
         body-edited :body/edited
         body-status :body/status
         :as arg} (rum/react *form)]
    [:.editor.row
     [:img.post_avatar {:src (fragments/avatar-url author)}]
     
     [:.column.grow
      (media arg)

      [:div (str body-status) " " (:body.status/message arg)]

      [:.textarea
       [:.input
        [:textarea {:placeholder "Be grumpy here..."
                    :value       (or body-edited body)
                    :on-change   #(on-body-edited! (-> % (oget "currentTarget") (oget "value")))}]]
       [:.handle.column.center
        [:.rope]
        [:.ring.cursor-pointer]]]

      (if new?
        (buttons-new arg)
        (buttons-edit arg))]]))


(def debug-forms
  [["New"                {}]
   ["Edit"               {:new? false :post {:id "0THGrh25y" :body "Livejournal mobile icons. My interpretation:\n\n- A guy was thinking about doing some writing. About heavy machinery, I suppose.\n- Nobody would publish his paper.\n- He brought two friends.\n- They wrote a book.\n- It gets published.\n- They receive a gift.\n- He starts thinking about next book, but one of the friends didn’t like it, so he left.\n- Some time later, second friend leaves too. He’s all alone again." :picture {:url "/post/0THGrh25y/M0C_mT1.fit.jpeg", :content-type "image/jpeg", :dimensions [750 265]}}}]
   ["Another author"     {:post {:author "freetonik"}}]
   ["Body changed"       {:post {:body "Hello, world!"} :body/edited "Hello, world! (edited)" :body/status :body.status/edited}]
   ["Body saving"        {:post {:body "Hello, world!"} :body/edited "Hello, world! (edited)" :body/status :body.status/saving}]
   ["Body failed"        {:post {:body "Hello, world!"} :body/edited "Hello, world! (edited)" :body/status :body.status/failed :body.status/message "Autosave failed"}]
   ["Dragged"            {:dragged? true}]
   ["Dragged over image" {:dragged? true :post {:picture {:url "/post/0THGrh25y/M0C_mT1.orig.png"}}}]
   ["Uploading 0%"       {:blob/url "/post/0THGrh25y/M0C_mT1.orig.png" :picture/status :picture.status/uploading :picture.status/progress 0}]
   ["Uploading 50%"      {:blob/url "/post/0THGrh25y/M0C_mT1.orig.png" :picture/status :picture.status/uploading :picture.status/progress 0.5}]
   ["Uploading tall 30%" {:blob/url "/post/0T6kDVGD2/LycvDQV.orig.jpeg" :picture/status :picture.status/uploading :picture.status/progress 0.3}]
   ["Upload failed"      {:blob/url "/post/0THGrh25y/M0C_mT1.orig.png" :picture/status :picture.status/failed :picture.status/message "500 Internal Server Error"}]
   ["Upload failed long" {:blob/url "/post/0THGrh25y/M0C_mT1.orig.png" :picture/status :picture.status/failed :picture.status/message "500 Internal Server Error Machine says no No way Nope NaN NaN NaN"}]
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


(rum/defc debug < rum/reactive []
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
     (editor (atom form')))])


(defn ^:after-load ^:export refresh []
  (let [mount  (js/document.querySelector ".mount")
        _      (when (nil? @*form)
                 (reset! *form
                   (-> (.getAttribute mount "data")
                     (edn/read-string))))
        debug? (re-find #"(\?|&|^)debug(&|$)" (or (oget js/location "search") ""))]
    (rum/mount (if debug? (debug) (editor *form)) mount)))