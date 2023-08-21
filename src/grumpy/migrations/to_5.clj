(ns grumpy.migrations.to-5
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [datascript.core :as d]
    [grumpy.core.files :as files]
    [grumpy.db :as db]
    [mount.core :as mount]))

(defn migrate! []
  (mount/start #'db/storage #'db/conn)
  (try
    (let [db    (db/db)
          media (d/q '[:find ?e ?url
                       :where
                       [?e :media/url ?url]
                       [?e :media/version 1]]
                  db)
          tx    (vec
                  (for [[eid url] media
                        :let [url' (str/replace url #"(\d+)(_1)(_full)?\.([a-z0-9]+)" "$1$3.$4")]]
                    (do
                      (files/move (io/file "grumpy_data" url) (io/file "grumpy_data" url'))
                      [:db/add eid :media/url url'])))]
      (db/transact! tx))
    (finally
      (mount/stop))))

(comment
  (migrate!))
