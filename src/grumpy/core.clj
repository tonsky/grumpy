(ns grumpy.core
  (:refer-clojure :exclude [slurp])
  (:require
    [rum.core :as rum]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [clojure.java.io :as io]
    [clojure.java.shell :as shell]
    [ring.util.mime-type :as mime-type]
    [grumpy.time :as time]
    [grumpy.transit :as transit])
  (:import
    [java.util UUID Random]
    [java.net URLEncoder]))


(defn slurp [source]
  (try
    (str/trim (clojure.core/slurp source))
    (catch Exception e
      nil)))


(.mkdirs (io/file "grumpy_data"))


(defmacro from-config [name default-value]
 `(let [file# (io/file "grumpy_data" ~name)]
    (when-not (.exists file#)
      (spit file# ~default-value))
    (str/trim (clojure.core/slurp file#))))


(def authors
  [{:email "niki@tonsky.me"
    :user  "nikitonsky"
    :telegram/user "nikitonsky" 
    :telegram/user-chat "232806939"
    :name  "Nikita Prokopov"
    :url   "https://twitter.com/nikitonsky"}
   {:email "freetonik@gmail.com"
    :user  "freetonik"
    :telegram/user "freetonik"
    :name  "Rakhim Davletkaliyev"
    :url   "https://twitter.com/freetonik"}
   {:email "ivan@grishaev.me"
    :user  "igrishaev"
    :telegram/user "igrishaev"
    :name  "Ivan Grishaev"
    :url   "https://grishaev.me/"}
   {:email "dmitrii@dmitriid.com"
    :user  "dmitriid"
    :telegram/user "mamutnespit"
    :telegram/user-chat "303519462"
    :name  "Dmitrii Dimandt"
    :url   "https://twitter.com/dmitriid"}
   {:email "ulyanovskui@gmail.com"
    :user  "andreivoronin"
    :telegram/user "andreuvoronin"
    :name  "Andrei Voronin"
    :url   "https://twitter.com/UlyanovskUI"}])


(defn author-by [attr value]
  (first (filter #(= (get % attr) value) authors)))


(def ^:dynamic hostname (from-config "HOSTNAME" "https://grumpy.website"))


(def forced-user (slurp "grumpy_data/FORCED_USER"))


(def ^:dynamic dev? (= "http://localhost:8080" hostname))


(defn zip [coll1 coll2]
  (map vector coll1 coll2))


(defn conjv [v x]
  (conj (vec v) x))


(defn make-uuid
  ([hi]
   (UUID. hi (.nextLong (Random.))))
  ([hi low]
   (UUID. hi low)))


(defn update-some [m key f & args]
  (if-some [value (get m key)]
    (assoc m key (apply f value args))
    m))


(defn filtermv [pred m]
  (reduce-kv (fn [m k v] (if (pred v) (assoc m k v) m)) {} m))


(defn seek [pred coll]
  (reduce #(when (pred %2) (reduced %2)) nil coll))


(def readers {'inst time/parse-iso-inst})


(defn read-edn-string [s]
  (edn/read-string {:readers readers} s))


(defn encode-uri-component [^String s]
  (-> s
      (URLEncoder/encode "UTF-8")
      (str/replace #"\+"   "%20")
      (str/replace #"\%21" "!")
      (str/replace #"\%27" "'")
      (str/replace #"\%28" "(")
      (str/replace #"\%29" ")")
      (str/replace #"\%7E" "~")))


(defn url [path query]
  (str
    path
    "?"
    (str/join "&"
      (map
        (fn [[k v]]
          (str (name k) "=" (encode-uri-component v)))
        query))))


(defn mime-type [filename]
  (mime-type/ext-mime-type filename { nil "application/octet-stream" }))


(defn content-type [picture]
  (let [mime-type (or (:content-type picture)
                      (mime-type (:url picture)))]
    (cond
      (str/starts-with? mime-type "video/") :content.type/video
      (str/starts-with? mime-type "image/") :content.type/image)))


(def ^:const encode-table "-0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz")


(defn encode [num len]
  (loop [num  num
         list ()
         len  len]
    (if (== 0 len)
      (str/join list)
      (recur (bit-shift-right num 6)
             (let [ch (nth encode-table (bit-and num 0x3F))]
              (conj list ch))
             (dec len)))))


(defn fit [x y maxx maxy]
  (cond
    (> x maxx)
      (fit maxx (* y (/ maxx x)) maxx maxy)
    (> y maxy)
      (fit (* x (/ maxy y)) maxy maxx maxy)
    :else
      [(int x) (int y)]))


(def checksum-resource
  (if dev?
    identity
    (memoize
      (fn [res]
        (let [contents (slurp (io/resource res))
              digest   (.digest (java.security.MessageDigest/getInstance "SHA-1") (.getBytes ^String contents))
              buffer   (StringBuilder. (* 2 (alength digest)))]
          (areduce digest i s buffer
            (doto s
              (.append (Integer/toHexString (unsigned-bit-shift-right (bit-and (aget digest i) 0xF0) 4)))
              (.append (Integer/toHexString (bit-and (aget digest i) 0x0F)))))
          (str res "?checksum=" (str buffer)))))))


(defn delete-dir [dir]
  (doseq [^java.io.File file (reverse (file-seq (io/file dir)))]
    (.delete file)))


(defn redirect
  ([path]
    { :status 302
      :headers { "Location" path } })
  ([path query]
    { :status 302
      :headers { "Location" (url path query) }}))


(defn get-post [post-id]
  (let [path (str "grumpy_data/posts/" post-id "/post.edn")]
    (some-> (io/file path)
      (slurp)
      (read-edn-string))))


(defn list-files
  ([dir] (seq (.list (io/file dir))))
  ([dir re]
    (seq
      (.list (io/file dir)
        (proxy [java.io.FilenameFilter] []
          (accept ^boolean [^java.io.File file ^String name]
            (boolean (re-matches re name))))))))


(defn post-ids []
  (->>
    (for [name (list-files "grumpy_data/posts")
          :let [child (io/file "grumpy_data/posts" name)]
          :when (.isDirectory child)]
      name)
    (sort)
    (reverse)))


(defn format-text [text]
  (->> (str/split text #"[\r\n]+")
    (map
      (fn [paragraph]
        (as-> paragraph paragraph
          ;; highlight links
          (str/replace paragraph #"https?://([^\s<>]+[^\s.,!?:;'\"<>()\[\]{}*])"
            (fn [[href path]]
              (let [norm-path     (re-find #"[^?#]+" path)
                    without-slash (str/replace norm-path #"/$" "")]
                (str "<a href=\"" href "\" target=\"_blank\">" without-slash "</a>"))))
          (str "<p>" paragraph "</p>"))))
    (str/join)))


(def resource
  (cond-> (fn [name]
            (clojure.core/slurp (io/resource (str "static/" name))))
    (not dev?) (memoize)))


(def style
  (memoize
    (fn [name]
      (if dev?
        [:link { :rel "stylesheet" :type "text/css" :href (str "/static/" name) }]
        (let [content (clojure.core/slurp (io/resource (str "static/" name)))]
          [:style { :type "text/css" :dangerouslySetInnerHTML { :__html content }}])))))


(rum/defc page [opts & children]
  (let [{:keys [title page subtitle? styles scripts]
         :or {title     "Grumpy Website"
              page      :other
              subtitle? true}} opts]
    [:html
      [:head
        [:meta { :http-equiv "Content-Type" :content "text/html; charset=UTF-8"}]
        [:meta { :name "viewport" :content "width=device-width, initial-scale=1.0"}]
        [:link { :href "/feed.xml" :rel "alternate" :title "Grumpy Website" :type "application/atom+xml" }]

        [:link { :href "/static/favicons/apple-touch-icon-152x152.png" :rel "apple-touch-icon-precomposed" :sizes "152x152" }]
        [:link { :href "/static/favicons/favicon-196x196.png" :rel "icon" :sizes "196x196" }]
        [:link { :href "/static/favicons/favicon-32x32.png"  :rel "icon" :sizes "32x32" }]

        [:title title]
        (style "styles.css")
        (for [css styles]
          (style css))
        [:script {:dangerouslySetInnerHTML { :__html (resource "scripts.js") }}]
        (for [script scripts]
          [:script {:dangerouslySetInnerHTML { :__html (resource script) }}])]
      [:body.anonymous
        [:header
          (case page
            :index [:h1.title title [:a.title_new { :href "/new" } "+"]]
            :post  [:h1.title [:a {:href "/"} title ]]
                   [:h1.title [:a.title_back {:href "/"} "◄"] title])
          (when subtitle?
            [:p.subtitle
              [:span.icon_rotate {:on-click "body_rotate()"}]
              [:span.subtitle-text " "]])]
        children
        (when (= page :index)
          [:.loader [:img { :src "/static/favicons/apple-touch-icon-152x152.png" }]])
        [:footer
          (interpose ", "
            (for [author authors]
              [:a { :href (:url author) } (:name author)]))
          ". 2019. All fights retarded."]]]))


(defn html-response [component]
  {:status  200
   :headers { "Content-Type" "text/html; charset=utf-8" }
   :body    (str "<!DOCTYPE html>\n" (rum/render-static-markup component))})


(defn transit-response [payload]
  {:status  200
   :headers {"Content-Type" "application/transit+json; charset=utf-8"}
   :body    (transit/write-transit-str payload)})


(defn try-async
  ([name f] (try-async name f {}))
  ([name f {:keys [after retries interval-ms]
            :or {after       identity
                 retries     5
                 interval-ms 1000}}]
    (future
      (try
        (loop [i 0]
          (if (< i retries)
            (let [[success? res] (try
                                  [true (f)]
                                  (catch Exception e
                                    (println "[" name "] Try #" i" failed" (pr-str (ex-data e)))
                                    (.printStackTrace e)
                                    [false nil]))]
              (if success?
                (after res)
                (do
                  (Thread/sleep interval-ms)
                  (recur (inc i)))))
              (println "[" name "] Giving up after" retries "retries")))
        (catch Exception e
          (println "[" name "] Something went wrong" (pr-str (ex-data e)))
          (.printStackTrace e))))))


(defn log [& args]
  (apply println (time/format-log-inst) args))


(Thread/setDefaultUncaughtExceptionHandler
  (reify Thread$UncaughtExceptionHandler
    (uncaughtException [_ thread ex]
      (log "Uncaught exception on" (.getName ^Thread thread))
      (.printStackTrace ^Throwable ex))))


(defn sh [& args]
  (apply log "sh:" (mapv pr-str args))
  (let [{:keys [exit out err] :as res} (apply shell/sh args)]
    (log "exit:" exit "out:" (pr-str out) "err:" (pr-str err))
    (if (= 0 exit)
      res
      (throw (ex-info (str "External process failed: " (str/join " " args) " returned " exit)
               (assoc res :args args))))))