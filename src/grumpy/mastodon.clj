(ns grumpy.mastodon
  (:refer-clojure
    :exclude [split-at])
  (:require
    [clj-http.client :as http]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [datascript.core :as d]
    [grumpy.core.coll :as coll]
    [grumpy.core.config :as config]
    [grumpy.core.jobs :as jobs]
    [grumpy.core.log :as log]
    [grumpy.core.time :as time]
    [grumpy.core.posts :as posts]
    [grumpy.db :as db]))


(def token
  (config/get-optional ::token))


(def headers
  {"Authorization" (str "Bearer " token)})


(def media-timeout-ms
  30000)


(defn find-crosspost [post]
  (coll/seek #(= :mstd (:crosspost/type %)) (:post/crosspost post)))


(defn upsert-crosspost [db post-eid data]
  (let [post      (d/entity db post-eid)
        crosspost (find-crosspost post)
        id        (or (:db/id crosspost) -1)]
    [[:db/add post-eid :post/crosspost id]
     (merge
       {:db/id id
        :crosspost/type :mstd}
       data)]))


(defn- post! [endpoint params]
  (log/log "POST" endpoint params)
  (let [t0   (time/now)
        resp (http/post (str "https://mastodon.online" endpoint)
               (merge 
                 {:headers headers
                  :as      :json}
                 params))]
    (log/log "Response for" endpoint "in" (time/since t0) "ms:" (:body resp))
    (:id (:body resp))))


(defn crosspost-media! [post]
  (when-some [media (posts/crosspost-media post)]
    (let [id (post! "/api/v2/media"
               {:multipart [{:name "file" :content (io/file "grumpy_data" (:media/url media))}]})
          t0 (time/now)]
      (loop []
        (let [resp (http/get (str "https://mastodon.online/api/v1/media/" id)
                     {:headers headers
                      :as :json})
              status (:status resp)]
          (cond
            (= 200 status)
            (db/transact!
              [[:db.fn/call upsert-crosspost (:db/id post) {:crosspost.mstd/media-id id}]])
          
            (and (= 206 status) (< (time/since t0) media-timeout-ms))
            (do
              (Thread/sleep 1000)
              (recur))
          
            (= 206 (:status resp))
            (throw (Exception. (str "Media " (:media/url media) " takes too long to process: " (- (System/currentTimeMillis) t0) " ms")))
            
            :else
            (throw (Exception. (str "Unexpected response to " (:media/url media) ": " (select-keys resp [:status :body]))))))))))


(defn find-last [^String s chars until]
  (transduce
    (map #(.lastIndexOf s ^String % (int until)))
    max
    -1
    chars))


(defn split-at [s pos]
  (let [i (find-last s [" " "\n"] pos)]
    (if (pos? i)
      [(subs s 0 i) (str/triml (subs s i))]
      [(subs s 0 pos) (subs s pos)])))


(defn split-impl [acc s]
  (cond
    (< (count s) 480)
    (let [acc (conj acc s)
          cnt (count acc)]
      (map-indexed 
        (fn [i s]
          (str 
            (when (> i 0) "... ")
            s
            (when (< i (dec cnt))
              " ...")
            " " (inc i) "/" cnt))
        acc))
         
    (< (count s) 960)
    (let [[s1 s2] (split-at s (quot (count s) 2))]
      (recur (conj acc s1) s2))
         
    :else
    (let [[s1 s2] (split-at s 480)]
      (recur (conj acc s1) s2))))


(defn split [s]
  (if (< (count s) 500)
    [s]
    (split-impl [] s)))


(defn crosspost-text! [post]
  (let [media-id (:crosspost.mstd/media-id (find-crosspost post))
        bodies   (split (:post/body post))
        ids      (loop [ids    []
                        i      0
                        bodies bodies]
                   (if-some [body (first bodies)]
                     (let [id (post! "/api/v1/statuses"
                                {:form-params
                                 {:status         body
                                  :visibility     "public"
                                  :language       "en"
                                  :media_ids      (when (= i 0)
                                                    (coll/some-vec media-id))
                                  :in_reply_to_id (peek ids)}
                                 :content-type :json})]
                       (recur (conj ids id) (inc i) (next bodies)))
                     ids))]
    (db/transact!
      [[:db.fn/call upsert-crosspost (:db/id post) {:crosspost.mstd/ids ids}]])))
    

(defn crosspost! [post]
  (when token
    (jobs/try-async
      (crosspost-media! post)
      (crosspost-text! (d/entity (db/db) (:db/id post))))))


(comment
  (def body
    (->> (d/datoms (db/db) :aevt :post/body)
      (map :v)
      (sort-by count)
      last))
  
  (doseq [s (split body)]
    (println s))
  
  (d/pull (db/db) '[*] [:post/id 272])
  (try
    (crosspost! (d/pull (db/db) '[*] [:post/id 272]))
    (catch Exception e
      (.printStackTrace e))))
