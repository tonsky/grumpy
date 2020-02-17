(ns grumpy.core.routes
  (:refer-clojure :exclude [sort])
  (:require
   [io.pedestal.http.route :as route]))


(defn- expand-route [[method path & interceptors]]
  (let [interceptors' (->> interceptors flatten (remove nil?) vec)
        handler       (last interceptors')
        name          (if (symbol? handler)
                        (keyword (str handler))
                        (keyword (str (name method) ":" path)))]
  [path method interceptors' :route-name name]))


(defn expand [& routes]
  (->> routes
    (map expand-route)
    (into #{})
    (route/expand-routes)))


(defn compare-parts [[p1 & ps1] [p2 & ps2]]
  (cond
    (and (nil? p1) (nil? p2)) 0
    (= p1 p2) (compare-parts ps1 ps2)
    (= (type p1) (type p2)) (compare p1 p2)
    (nil? p1) -1
    (nil? p2) 1
    (string? p1) -1
    (string? p2) 1
    :else (compare p1 p2)))


(defn sort [routes]
  (sort-by :path-parts compare-parts routes))