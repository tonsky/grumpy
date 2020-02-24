(ns grumpy.editor.body
  (:require
   [grumpy.core.fetch :as fetch]
   [grumpy.core.macros :refer [oget oset! cond+]]
   [grumpy.core.transit :as transit]
   [rum.core :as rum]))


(def AUTOSAVE_PERIOD_MS 1000) ;; FIXME


(declare to-saving)


(defn schedule-to-saving [*form]
  (when (nil? (:body/timer @*form))
    (swap! *form assoc :body/timer (js/setTimeout #(to-saving *form) AUTOSAVE_PERIOD_MS))))


(defn to-idle [*form saved]
  (when (= (:body/edited @*form) saved)
    (swap! *form #(-> %
                    (dissoc :body/edited)
                    (dissoc :body/status)
                    (dissoc :body.status/message)
                    (assoc-in [:post :body] saved)))))


(defn to-failed [*form msg]
  (swap! *form assoc
    :body/status :body.status/failed
    :body.status/message msg)
  (schedule-to-saving *form))


(defn to-saving [*form]
  (let [form @*form
        edited (:body/edited form)]
    (when (not= edited (:body (:post form)))
      (swap! *form #(-> %
                      (dissoc :body/timer)
                      (assoc :body/status :body.status/saving)))
      (fetch/fetch! "POST" (str "/post/" (:post-id form) "/update-body")
        {:body    (transit/write-transit-str {:post {:body edited}})
         :success (fn [payload] (to-idle *form edited))
         :error   (fn [payload] (to-failed *form payload))}))))


(defn to-edited [*form value]
  (swap! *form #(-> %
                  (dissoc :body.status/message)
                  (assoc :body/edited value)
                  (assoc :body/status :body.status/edited))
  (schedule-to-saving *form)))


(rum/defc ui < rum/reactive [*form]
  [:.textarea
   [:div
    (str (or (rum/react (rum/cursor *form :body/status)) "👍 Saved"))
    " "
    (rum/react (rum/cursor *form :body.status/message))]
   [:.input
    [:textarea {:placeholder "Be grumpy here..."
                :value       (or (rum/react (rum/cursor *form :body/edited))
                               (rum/react (rum/cursor-in *form [:post :body])))
                :on-change   #(to-edited *form (-> % (oget "currentTarget") (oget "value")))}]]
   [:.handle.column.center
    [:.rope]
    [:.ring.cursor-pointer]]])
