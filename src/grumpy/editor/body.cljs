(ns grumpy.editor.body
  (:require
   [grumpy.core.coll :as coll]
   [grumpy.core.fetch :as fetch]
   [grumpy.core.fragments :as fragments]
   [grumpy.core.macros :refer [oget oset! cond+]]
   [grumpy.core.transit :as transit]
   [rum.core :as rum]))


(def AUTOSAVE_PERIOD_MS 5000)


(declare to-saving)


(defn schedule-to-saving [*post]
  (when (nil? (:body/autosave-timer @*post))
    (swap! *post assoc :body/autosave-timer (js/setTimeout #(to-saving *post) AUTOSAVE_PERIOD_MS))))


(defn to-idle [*post saved]
  (when (= (:body/edited @*post) saved)
    (swap! *post coll/replace
      #":body/.*"
      :body saved)))


(defn to-failed [*post msg]
  (swap! *post assoc
    :body/status :body.status/failed
    :body/error msg)
  (schedule-to-saving *post))


(defn to-saving [*post]
  (let [post @*post
        edited (:body/edited post)]
    (when (not= edited (:body post))
      (swap! *post coll/replace
        #":body/(?!edited).*"
        :body/status :body.status/saving)
      (fetch/post! (str "/draft/" (:id post) "/update-body")
        {:body    edited
         :success (fn [payload] (to-idle *post edited))
         :error   (fn [payload] (to-failed *post payload))}))))


(defn to-edited [*post value]
  (swap! *post coll/replace
    #":body/(?!autosave-timer).*"
    :body/edited value
    :body/status :body.status/edited)
  (schedule-to-saving *post))


(defn on-mouse-down [*drag-state input e]
  (reset! *drag-state
    {:input       input
     :base-height (oget input "clientHeight")
     :base-y      (oget e "clientY")})
  (.preventDefault e))


(defn on-mouse-move [*drag-state e]
  (when-some [{:keys [input base-height base-y]} @*drag-state]
    (let [height (-> base-height
                   (+ (- (oget e "clientY") base-y))
                   (max 100))]
      (oset! (oget input "style") "height" (str height "px")))))


(defn on-mouse-up [*drag-state e]
  (when (some? @*drag-state)
    (reset! *drag-state nil)
    (.preventDefault e)))


(rum/defc handle < rum/static
  {:did-mount
   (fn [state]
     (let [*drag-state   (atom nil)
           input         (js/document.querySelector ".textarea > .input")
           ring          (-> (rum/dom-node state) (.querySelector ".ring"))
           on-mouse-down #(on-mouse-down *drag-state input %)
           body          js/document.documentElement
           on-mouse-move #(on-mouse-move *drag-state %)
           on-mouse-up   #(on-mouse-up *drag-state %)]
       (.addEventListener ring "mousedown" on-mouse-down)
       (.addEventListener body "mousemove" on-mouse-move)
       (.addEventListener body "mouseup" on-mouse-up)
       (assoc state
         ::on-mouse-down on-mouse-down
         ::on-mouse-move on-mouse-move
         ::on-mouse-up   on-mouse-up)))
   :will-unmount
   (fn [state]
     (let [ring (-> (rum/dom-node state) (.querySelector ".ring"))
           body js/document.documentElement]
       (.removeEventListener ring "mousedown" (::on-mouse-down state))
       (.removeEventListener body "mousemove" (::on-mouse-move state))
       (.removeEventListener body "mouseup"   (::on-mouse-up state))
       (dissoc state ::on-mouse-down ::on-mouse-move ::on-mouse-up)))}
  []
  [:.handle.column.center
   [:.rope]
   [:.ring.cursor-pointer]])


(rum/defc ui < rum/reactive [*post]
  (let [disabled? (some? (fragments/subscribe *post :post/status))]
    [:.textarea
     #_[:div
      (str (or (fragments/subscribe *post :body/status) "👍 Saved"))
      " "
      (fragments/subscribe *post :body/error)]
     [:.input {:class (when disabled? "disabled")}
      [:textarea {:disabled disabled?
                  :placeholder "Be grumpy here..."
                  :default-value (or (fragments/subscribe *post :body/edited)
                                   (fragments/subscribe *post :body)
                                   "")
                  :on-change   #(to-edited *post (-> % (oget "currentTarget") (oget "value")))}]]
     (handle)]))
