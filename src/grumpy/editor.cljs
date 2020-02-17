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
         [:.media-delete.cursor-pointer]]
        [:.upload.no-select.cursor-pointer
         [:.corner.top-left]
         [:.corner.top-right]
         [:.corner.bottom-left]
         [:.corner.bottom-right]
         [:.label "Drag media here"]])

      [:.textarea
       [:textarea {:placeholder "Be grumpy here..." :value body}]
       [:.handle.column.center
        [:.rope]
        [:.ring.cursor-pointer]]]]

     [:.extra-col.self-hstretch.grid.middle
      [:.author-label.cursor-default "Author"]
      [:input.author-input {:type "text" :value author}]

      [:.status-label.cursor-default "Status"]
      [:.status.row.cursor-default
       [:.icon]
       [:.label "Saved"]]]

     [:button.post-post.row
      [:img.button {:src "/static/editor/post_button.svg"}]
      [:img.hand {:src "/static/editor/post_hand.svg"}]
      [:.label (if (some? id) "Update" "POST")]]

     [:button.post-delete.self-middle.btn.secondary
       (if (some? id) "Cancel edit" "Delete draft")]]))


(def debug-states
  [["New"              {}]
   ["Another author"   {:post {:author "freetonik"}}]
   ["Anonymous author" {:post {:author "abc"}}]
   ["Body changed"     {:post {:body "Hello, world!"}}]
   ["Dragged"          {}]
   ["Sending"          {:post {:picture {:url "/post/0THGrh25y/M0C_mT1.orig.png"}}}]
   ["Sending vertical" {:post {:picture {:url "/post/0TDdxMGm8/M-Jw_2o.orig.png"}}}]
   ["Image uploaded"   {:post {:picture {:url "/post/0THGrh25y/M0C_mT1.fit.jpeg", :content-type "image/jpeg", :dimensions [750 265]}}}]
   ["Edit"             {:post {:id "0THGrh25y"}}]])


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