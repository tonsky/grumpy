(ns grumpy.core.macros)


(defmacro oget [obj key]
  (list 'js* "(~{}[~{}])" obj key))


(defmacro oset! [obj key val]
  (list 'js* "(~{}[~{}] = ~{})" obj key val))


(defmacro js-fn [& body]
  (if (:ns &env) ;; cljs
    `(fn ~@body)
    `nil))


(defmacro cond+ [& clauses]
  (when-some [[test expr & rest] clauses]
    (case test
      :let `(let ~expr (cond+ ~@rest))
      :do  `(do ~expr (cond+ ~@rest))
      `(if ~test ~expr (cond+ ~@rest)))))



