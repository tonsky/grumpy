(ns grumpy.core.coll
  (:refer-clojure :exclude [replace]))


(defn zip [coll1 coll2]
  (map vector coll1 coll2))


(defn conjv [v x]
  (conj (vec v) x))


(defn update-some [m key f & args]
  (if-some [value (get m key)]
    (assoc m key (apply f value args))
    m))


(defn filtermv [pred m]
  (reduce-kv (fn [m k v] (if (pred v) (assoc m k v) m)) {} m))


(defn seek [pred coll]
  (reduce #(when (pred %2) (reduced %2)) nil coll))


(defn assoc-new [m k v]
  (if (contains? m k)
    m
    (assoc m k v)))


(defn deep-merge [o1 o2]
  (if (and (map? o1) (map? o2))
    (merge-with deep-merge o1 o2)
    o2))


(defn dissoc-all [m re]
  (reduce-kv (fn [m k v] (if (re-matches re (str k)) (dissoc m k) m)) m m))


(defn replace [m re & kvs]
  (apply assoc (dissoc-all m re) kvs))