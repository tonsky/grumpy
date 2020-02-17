(ns grumpy.core.coll)


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