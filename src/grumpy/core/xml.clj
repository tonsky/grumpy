(ns grumpy.core.xml
  (:require
   [clojure.string :as str]))


(defn- escape [s]
  (-> s
    (str/replace "&"  "&amp;")
    (str/replace "\"" "&quot;")
    (str/replace "'"  "&apos;")
    (str/replace "<"  "&lt;")
    (str/replace ">"  "&gt;")))


(defn- emit-impl [^StringBuilder sb indent el]
  (cond
    (vector? el)
    (let [[tag attrs & children] el]
      (.append sb indent)
      (.append sb "<")
      (.append sb (name tag))
      (doseq [[attr value] attrs]
        (.append sb " ")
        (.append sb (name attr))
        (.append sb "=\"")
        (.append sb (escape (str value)))
        (.append sb "\""))
      (.append sb ">")
      (cond
        (empty? children)
        :noop

        (every? string? children)
        (doseq [child children]
          (emit-impl sb indent child))

        :else
        (do
          (.append sb "\n")
          (doseq [child children]
            (emit-impl sb (str indent "  ") child))
          (.append sb indent)))
      (.append sb "</")
      (.append sb (name tag))
      (.append sb ">\n"))

    (sequential? el)
    (doseq [el el]
      (emit-impl sb indent el))

    :else
    (.append sb (escape (str el)))))


(defn emit [el]
  (let [sb (StringBuilder. "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")]
    (emit-impl sb "" el)
    (str sb)))