(ns grumpy.editor.body
  (:require
   [grumpy.core.coll :as coll]
   [grumpy.core.fetch :as fetch]
   [grumpy.core.macros :refer [oget oset! cond+]]
   [grumpy.core.transit :as transit]
   [rum.core :as rum]))


(def AUTOSAVE_PERIOD_MS 1000) ;; FIXME


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
    :body/failed-message msg)
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


(rum/defc ui < rum/reactive [*post]
  [:.textarea
   [:div
    (str (or (rum/react (rum/cursor *post :body/status)) "👍 Saved"))
    " "
    (rum/react (rum/cursor *post :body/failed-message))]
   [:.input
    [:textarea {:placeholder "Be grumpy here..."
                :value       (or (rum/react (rum/cursor *post :body/edited))
                               (rum/react (rum/cursor *post :body))
                               "")
                :on-change   #(to-edited *post (-> % (oget "currentTarget") (oget "value")))}]]
   [:.handle.column.center
    [:.rope]
    [:.ring.cursor-pointer]]])
