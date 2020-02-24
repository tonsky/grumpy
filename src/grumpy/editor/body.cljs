(ns grumpy.editor.body
  (:require
   [grumpy.core.fetch :as fetch]
   [grumpy.core.macros :refer [oget oset! cond+]]
   [grumpy.core.transit :as transit]
   [rum.core :as rum]))


(def AUTOSAVE_PERIOD_MS 1000) ;; FIXME


(declare to-saving)


(defn schedule-to-saving [*post]
  (when (nil? (:body/timer @*post))
    (swap! *post assoc :body/timer (js/setTimeout #(to-saving *post) AUTOSAVE_PERIOD_MS))))


(defn to-idle [*post saved]
  (when (= (:body/edited @*post) saved)
    (swap! *post #(-> %
                    (dissoc :body/edited)
                    (dissoc :body/status)
                    (dissoc :body.status/message)
                    (assoc :body saved)))))


(defn to-failed [*post msg]
  (swap! *post assoc
    :body/status :body.status/failed
    :body.status/message msg)
  (schedule-to-saving *post))


(defn to-saving [*post]
  (let [post @*post
        edited (:body/edited post)]
    (when (not= edited (:body post))
      (swap! *post #(-> %
                      (dissoc :body/timer)
                      (assoc :body/status :body.status/saving)))
      (fetch/fetch! "POST" (str "/post/" (:id post) "/update-body")
        {:body    edited
         :success (fn [payload] (to-idle *post edited))
         :error   (fn [payload] (to-failed *post payload))}))))


(defn to-edited [*post value]
  (swap! *post #(-> %
                  (dissoc :body.status/message)
                  (assoc :body/edited value)
                  (assoc :body/status :body.status/edited))
  (schedule-to-saving *post)))


(rum/defc ui < rum/reactive [*post]
  [:.textarea
   [:div
    (str (or (rum/react (rum/cursor *post :body/status)) "👍 Saved"))
    " "
    (rum/react (rum/cursor *post :body.status/message))]
   [:.input
    [:textarea {:placeholder "Be grumpy here..."
                :value       (or (rum/react (rum/cursor *post :body/edited))
                               (rum/react (rum/cursor *post :body))
                               "")
                :on-change   #(to-edited *post (-> % (oget "currentTarget") (oget "value")))}]]
   [:.handle.column.center
    [:.rope]
    [:.ring.cursor-pointer]]])
