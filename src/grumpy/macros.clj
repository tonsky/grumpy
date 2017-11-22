(ns grumpy.macros)


(defmacro oget [obj key]
  (list 'js* "(~{}[~{}])" obj key))


(defmacro oset! [obj key val]
  (list 'js* "(~{}[~{}] = ~{})" obj key val))


(defmacro js-fn [& body]
  (if (:ns &env) ;; cljs
    `(fn ~@body)
    `nil))