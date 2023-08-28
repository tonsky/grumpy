(ns grumpy.server
  (:require
    [clojure.stacktrace :as stacktrace]
    [clojure.string :as str]
    [datascript.core :as d]
    [grumpy.auth :as auth]
    [grumpy.core.config :as config]
    [grumpy.core.fragments :as fragments]
    [grumpy.core.log :as log]
    [grumpy.core.mime :as mime]
    [grumpy.core.posts :as posts]
    [grumpy.core.routes :as routes]
    [grumpy.core.time :as time]
    [grumpy.core.web :as web]
    [grumpy.db :as db]
    [grumpy.feed :as feed]
    [grumpy.editor :as editor]
    [grumpy.search :as search]
    [io.pedestal.interceptor :as interceptor]
    [io.pedestal.http :as http]
    [mount.core :as mount]
    [ring.util.response :as response]
    [rum.core :as rum])
  (:import
    [java.io IOException]))


(def paginator-size
  8)


(defn pages [db page]
  (let [db             (db/db)
        total-pages    (posts/total-pages db)
        max-page       (dec total-pages) ;; / page always includes two last pages
        page           (if (neg? page) (- max-page (inc page)) page) ;; -1 for index
        half-paginator (quot paginator-size 2)
        pages          (cond
                         (< (- max-page page) (+ 2 half-paginator))
                         (take (+ 2 paginator-size) (range max-page 0 -1))
                         
                         (< (- page 1) half-paginator)
                         (range (min paginator-size max-page) 0 -1)
                         
                         :else
                         (let [from (-> page (+ half-paginator) dec)
                               till (- from paginator-size)]
                           (range from till -1)))]
    [:.pages
     [:.pages_title
      "Pages: "]
     [:.pages_inner
      (when (< (first pages) max-page)
        (list
          [:a {:href "/"} max-page]
          [:span "..."]))
      (for [p pages]
        (if (= p page)
          [:span.selected (str p)]
          [:a {:href (if (= max-page p) "/" (str "/page/" p))} (str p)]))
      (when (> (last pages) 1)
        [:span "..."])]]))


(rum/defc index-page []
  (let [db          (db/db)
        total-pages (posts/total-pages db)
        from        (inc (* config/page-size (- total-pages 2))) ;; / always shows last two pages: 1 incomplete + 1 complete
        till        (* config/page-size total-pages)
        ids         (->> (d/index-range db :post/id from till)
                      (rseq)
                      (map :v))]
    (web/page {:page :index}
      (list
        (map #(fragments/post (d/entity db [:post/id %])) ids)
        (pages db -1)))))


(rum/defc page-page [page]
  (let [db          (db/db)
        total-pages (posts/total-pages db)]
    (if (<= 1 page total-pages)
      (let [from (inc (* config/page-size (dec page)))
            till (* config/page-size page)
            ids  (->> (d/index-range db :post/id from till)
                   (rseq)
                   (map :v))]
        (web/html-response
          (web/page {:page :page}
            (list
              (map #(fragments/post (d/entity db [:post/id %])) ids)
              (pages db page)))))
      {:status 404
       :body   "Not found"})))


(rum/defc post-page [post-id]
  (web/page {:page :post}
    (fragments/post (d/entity (db/db) [:post/id post-id]))))


(rum/defc subscribe-page []
  (web/page {:page :subscribe}
    [:.page
     [:p "You can subscribe to Grumpy Website updates in multiple ways:"]
     [:ul
      [:li "Via RSS: " [:a {:href "https://grumpy.website/feed.xml"} "https://grumpy.website/feed.xml"]]
      [:li "Via Mastodon: " [:a {:href "https://mastodon.online/@grumpy_website"} "@grumpy_website@mastodon.online"]]
      [:li "Via Telegram Channel: " [:a {:href "https://t.me/whining"} "@whining"] " (posts only)"]
      [:li "Via Telegram Group: " [:a {:href "https://t.me/grumpy_chat"} "@grumpy_chat"] " (posts + discussions, mostly in Russian)"]]]))


(rum/defc suggest-page []
  (web/page {:page :suggest}
    [:.page
     [:p "You can suggest a post to Grumpy Website in multiple ways:"]
     [:ul
      [:li "Via email: " [:a {:href "mailto:grumpy@tonsky.me"} "grumpy@tonsky.me"]]
      [:li "Via Telegram DM: " [:a {:href "https://t.me/nikitonsky"} "@nikitonsky"]]
      [:li "Via Telegram Group: " [:a {:href "https://t.me/grumpy_chat"} "@grumpy_chat"]]]
     [:p "Please send:"]
     [:ul
      [:li "Single picture or video, maybe with comment what’s wrong"]
      [:li "Your (nick)name, or if you want to stay anonymous"]]
     [:p "Contribution guidlines:"]
     [:ul
      [:li "We do not guarantee that every submission will be published"]
      [:li "Publishing might take a while"]
      [:li "We will write our own text, but mention who sent the picture"]
      [:li "We prefer to focus on bad trends/intentionally bad decisions, not just funny bugs"]]
     [:p "If that works for you, we’ll be happy to take in your suggestions!"]]))


(def *contributors
  (atom nil))


(defn contributors [db]
  (->> (d/datoms db :aevt :post/body)
    (map :v)
    (keep #(re-find #"(?<=(?:^|[> ])@)[A-Za-z0-9_]+" %))
    (map #(vector (str/lower-case %) %))
    (into {})
    (#(dissoc % "nikitonsky" "dmitriid" "mamutnespit" "igrishaev" "mention" "firacode"))
    (vals)
    (sort-by str/lower-case)))


(defn cached-contributors []
  (let [db (db/db)
        [cached-db cached-contrib] @*contributors]
    (if (identical? db cached-db)
      cached-contrib
      (second (reset! *contributors [db (contributors db)])))))


(rum/defc about-page []
  (web/page {:page :about}
    [:.page
     [:p "Grumpy Website is a world-leading media conglomerate of renowned experts in UIs, UX and TVs."]
     [:p "We’ve been reporting on infinite scrolls, cookie banners and unnecessary modal dialogs since 2017."]
     [:h2 "Creators and authors:"]
     [:ul
      [:li [:a {:href "https://mastodon.online/@nikitonsky"} "Nikita Prokopov"]]
      [:li [:a {:href "https://twitter.com/freetonik"} "Rakhim Davletkaliyev"]]
      [:li [:a {:href "https://twitter.com/dmitriid"} "Dmitrii Dimandt"]]
      [:li [:a {:href "https://grishaev.me/"} "Ivan Grishaev"]]]
     [:h2 "With contributions from:"]
     [:ul
      (for [who (cached-contributors)]
        [:li [:a {:href (str "/search?q=@" who)} who]])]]))


(def no-cache
  (interceptor/interceptor
    {:name  ::no-cache
     :leave (fn [ctx]
              (let [h (:headers (:response ctx))]
                (if (contains? h "Cache-Control")
                  ctx
                  (update ctx :response assoc :headers (assoc h "Cache-Control" "no-cache", "Expires" "-1")))))}))

(def routes
  (routes/expand
    [:get "/post/:post-id/:img"
     (fn [{{:keys [post-id img]} :path-params}]
       (let [db  (db/db)
             url (->> (d/find-datom db :avet :media/old-url img)
                   :e (d/entity db) :media/url)]
         (web/moved-permanently (str "/media/" url))))]

    [:get "/media/*path"
     (fn [{{:keys [path]} :path-params}]
       (response/file-response (str "grumpy_data/" path)))]
    
    [:get "/post/:post-id"
     (fn [{{:keys [post-id]} :path-params}]
       (let [post (d/entity (db/db) [:post/old-id post-id])]
         (web/moved-permanently (str "/" (:post/id post)))))]
    
    [:get "/:post-id"
     (fn [{{:keys [post-id]} :path-params}]
       (let [post-id (parse-long post-id)
             post    (d/entity (db/db) [:post/id post-id])]
         (cond
           (nil? post)
           {:status 404
            :body   "Not found"}
           
           (:post/deleted? post)
           {:status 404
            :body   "Deleted"}
           
           :else
           (web/html-response (post-page post-id)))))]

    [:get "/"
     (when config/dev? auth/populate-session)
     (fn [_]
       (web/html-response (index-page)))]
    
    [:get "/page/:page"
     (when config/dev? auth/populate-session)
     (fn [req]
       (let [page (-> req :path-params :page parse-long)]
         (page-page page)))]
    
    [:get "/search"
     (when config/dev? auth/populate-session)
     (fn [req]
       (web/html-response
         (search/search-page (:query-params req))))]
    
    [:get "/subscribe"
     (when config/dev? auth/populate-session)
     (fn [req]
       (web/html-response
         (subscribe-page)))]
    
    [:get "/suggest"
     (when config/dev? auth/populate-session)
     (fn [req]
       (web/html-response
         (suggest-page)))]
    
    [:get "/about"
     (when config/dev? auth/populate-session)
     (fn [req]
       (web/html-response
         (about-page)))]

    [:get "/static/*path" 
     (when-not config/dev?
       {:leave #(update-in % [:response :headers] assoc "Cache-Control" "max-age=315360000")})
     (fn [{{:keys [path]} :path-params}]
       (response/resource-response (str "static/" path)))]

    [:get "/feed.xml"
     (fn [_]
       {:status  200
        :headers {"Content-Type" "application/atom+xml; charset=utf-8"}
        :body    (feed/feed (take 10 (posts/post-ids))) })]

    [:get "/sitemap.xml"
     (fn [_]
       {:status 200
        :headers {"Content-Type" "text/xml; charset=utf-8"}
        :body (feed/sitemap (posts/post-ids))})]

    [:get "/robots.txt"
     (fn [_]
       {:status 200
        :headers {"Content-Type" "text/plain"}
        :body (web/resource "robots.txt")})]))


; Filtering out Broken pipe reporting
; io.pedestal.http.impl.servlet-interceptor/error-stylobate
(defn error-stylobate [{:keys [servlet-response] :as context} exception]
  (let [^Throwable cause (stacktrace/root-cause exception)]
    (cond
      (and (instance? IOException cause) (= "Broken pipe" (.getMessage cause)))
      :ignore ; (println "Ignoring java.io.IOException: Broken pipe")

      (and (instance? IOException cause) (= "Connection reset by peer" (.getMessage cause)))
      :ignore ; (println "Ignoring java.io.IOException: Connection reset by peer")

      :else
      (io.pedestal.log/error
        :msg "error-stylobate triggered"
        :exception exception
        :context context))
    (@#'io.pedestal.http.impl.servlet-interceptor/leave-stylobate context)))

; io.pedestal.http.impl.servlet-interceptor/stylobate
(def stylobate
  (io.pedestal.interceptor/interceptor
    {:name ::stylobate
     :enter @#'io.pedestal.http.impl.servlet-interceptor/enter-stylobate
     :leave @#'io.pedestal.http.impl.servlet-interceptor/leave-stylobate
     :error error-stylobate}))

(def *opts
  (atom
    {:host "localhost"
     :port 8080}))

(mount/defstate server
  :start
  (with-redefs [io.pedestal.http.impl.servlet-interceptor/stylobate stylobate]
    (let [{:keys [host port]} @*opts]
      (log/log "[server] Starting web server at" (str host ":" port))
      (-> {::http/routes (routes/sort (concat routes auth/routes editor/routes))
           ::http/router :linear-search
           ::http/type   :immutant
           ::http/host   host
           ::http/port   port
           ::http/secure-headers {:content-security-policy-settings "object-src 'none'; script-src 'self' 'unsafe-inline' 'unsafe-eval'"}}
        (http/default-interceptors)
        (update ::http/interceptors conj no-cache)
        (http/create-server)
        (http/start))))
  :stop
  (do
    (log/log "[server] Stopping web server")
    (http/stop server)))
