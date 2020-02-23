(ns grumpy.editor.body
  (:require
   [grumpy.core.fetch :as fetch]
   [grumpy.core.macros :refer [oget oset! cond+]]
   [grumpy.core.transit :as transit]
   [rum.core :as rum]))


(def AUTOSAVE_PERIOD_MS 1000) ;; FIXME


(declare body->saving)


(defn schedule-body->saving [*form]
  (when (nil? (:body/timer @*form))
    (swap! *form assoc :body/timer (js/setTimeout #(body->saving *form) AUTOSAVE_PERIOD_MS))))


(defn body->idle [*form saved]
  (when (= (:body/edited @*form) saved)
    (swap! *form #(-> %
                    (dissoc :body/edited)
                    (dissoc :body/status)
                    (dissoc :body.status/message)
                    (assoc-in [:post :body] saved)))))


(defn body->failed [*form msg]
  (swap! *form assoc
    :body/status :body.status/failed
    :body.status/message msg)
  (schedule-body->saving *form))


(defn body->saving [*form]
  (let [form @*form
        edited (:body/edited form)]
    (when (not= edited (:body (:post form)))
      (swap! *form #(-> %
                      (dissoc :body/timer)
                      (assoc :body/status :body.status/saving)))
      (fetch/fetch! "POST" (str "/post/" (:post-id form) "/update-body")
        {:body    (transit/write-transit-str {:post {:body edited}})
         :success (fn [payload] (body->idle *form edited))
         :error   (fn [payload] (body->failed *form payload))}))))


(defn body->edited [*form value]
  (swap! *form #(-> %
                  (dissoc :body.status/message)
                  (assoc :body/edited value)
                  (assoc :body/status :body.status/edited))
  (schedule-body->saving *form)))


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
                :on-change   #(body->edited *form (-> % (oget "currentTarget") (oget "value")))}]]
   [:.handle.column.center
    [:.rope]
    [:.ring.cursor-pointer]]])
