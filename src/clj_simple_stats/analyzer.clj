(ns clj-simple-stats.analyzer
  (:require
   [clojure.string :as str])
  (:import
   [java.nio ByteBuffer]
   [java.security MessageDigest]
   [java.time LocalDate LocalTime]
   [java.util UUID]))

(defn- dequote [s]
  (when s
    (if (and
          (str/starts-with? s "\"")
          (str/ends-with? s "\""))
      (subs s 1 (- (count s) 1))
      s)))

(defn line-agent [{:keys [user-agent]}]
  (when user-agent
    (let [user-agent (dequote user-agent)]
      (or
        ;; Special cases
        ; Mozilla/4.0 Leed (LightFeed Aggregator) dev by idleman http://projet.idleman.fr/leed
        ; Mozilla/4.0 Leed (LightFeed Agrgegator) Stable by idleman http://projet.idleman.fr/leed
        ; Mozilla/5.0 (Linux; U; en-us; BeyondPod 4)
        ; Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36 Edg/140.0.0.0; 360Spider
        ; Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36/Nutch-1.21-SNAPSHOT
        ; Mozilla/5.0 (Windows NT 6.1; WOW64) SkypeUriPreview Preview/0.5 skype-url-preview@microsoft.com
        ; Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4450.0 Safari/537.36 LarkUrl
        ; Mozilla/5.0 (Linux; Android 12; redroid12_arm64 Build/SQ1D.220205.004; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/122.0.6261.119 Mobile Safari/537.36 uni-app
        (re-find #"(?i)(?:Leed|BeyondPod|360Spider|Lark|Nutch|Skype|leakix\.net|uni-app)" user-agent)

        ; 27F9D996-DF69-4753-A16D-76FBB0D682A3/310 CFNetwork/3826.400.120 Darwin/24.3.0
        (re-find #"(?i)(?<=^[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}/\d+ )[^;(/]+" user-agent)

        ;; After "compatible"
        ; Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko; compatible; bingbot/2.0; +http://www.bing.com/bingbot.htm) Chrome/116.0.1938.76 Safari/537.36
        ; Mozilla/5.0 (compatible; archive.org_bot +http://archive.org/details/archive.org_bot) Zeno/33782fc warc/v0.8.90
        ; Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 5.2; Trident/5.0)
        ; Mozilla/5.0 (Linux; Android 6.0.1; Nexus 5X Build/MMB29P) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.7339.207 Mobile Safari/537.36 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)
        ; Mozilla/5.0 (Linux; Android 5.0) AppleWebKit/537.36 (KHTML, like Gecko) Mobile Safari/537.36 (compatible; TikTokSpider; ttspider-feedback@tiktok.com)
        ; Mozilla/5.0 (Linux; Android 7.0;) AppleWebKit/537.36 (KHTML, like Gecko) Mobile Safari/537.36 (compatible; PetalBot;+https://webmaster.petalsearch.com/site/petalbot)
        (re-find #"(?i)(?<=compatible; )[^;(/+]*[^;(/+ ]" user-agent)

        ;; Before (ro)?bot
        ; progresswall.com robot
        ; star-finder.de Bot
        ; RSS Bot/2.8 (Mac OS X Version 10.14.5 (Build 18F132))
        (re-find #"(?i)^[\w\.\-_@ ]*[\w\.\-_@] (?:ro)?bot" user-agent)

        ;; Contains "bot"
        ; Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15 (Applebot/0.1; +http://www.apple.com/go/applebot)
        ; keys-so-bot
        ; Slackbot-LinkExpanding 1.0 (+https://api.slack.com/robots)
        ; StreamReader bot : http://rss.nhenry.fr/
        ; RSSingBot (http://www.rssing.com)
        (re-find #"(?i)\b[\w-_]+bot\b" user-agent)

        ;; IE
        ; Mozilla/5.0 (Windows NT 6.1; WOW64; Trident/7.0; PRU_IE; rv:11.0) like Gecko
        (re-find #"Trident(?=/[0-9.]+)" user-agent)

        ;; First component
        ; Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/28.0 Chrome/130.0.0.0 Mobile Safari/537.36
        ; Mozilla/5.0 (iPhone; CPU iPhone OS 17_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) EdgiOS/121.0.2277.107 Version/17.0 Mobile/15E148 Safari/604.1
        (re-find #"(?i)(?<=^Mozilla/.* )(?!Chrome|Version|Mobile|Safari|Mobile Safari)\w+(?=/[A-Z0-9.]+(?: (?:Chrome|Version|Mobile|Safari|Mobile Safari)/[A-Z0-9.]+)+$)" user-agent)

        ;; Before "Mobile? Safari"
        ; Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36
        ; Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36
        ; Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.200 YaBrowser/24.1.0.0 Safari/537.36
        ; Mozilla/5.0 (Linux; U; Android 11; en-US; V2027) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/89.0.4389.116 UCBrowser/13.4.0.1306 Mobile Safari/537.36
        (re-find #"(?i)(?<=^Mozilla/.* )(?!Version)\w+(?=/[0-9.]+(?: Mobile)? Safari/[0-9.]+$)" user-agent)

        ;; Last component
        ; Mozilla/5.0 (iPhone; CPU iPhone OS 10_3_1 like Mac OS X) AppleWebKit/603.1.30 (KHTML, like Gecko) Version/10.0 Mobile/14E304 Safari/602.1
        ; Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/14.0.3 Safari/605.1.15
        ; Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0
        ; Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36 Edg/141.0.0.0
        ; Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36 OPR/121.0.0.0
        ; Mozilla/5.0 (Macintosh; Intel Mac OS X 10_10_3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/41.0.2272.105 Safari/537.36 Vivaldi/1.0.162.9
        ; Mozilla/5.0 (Linux; U; Android 15; zh-cn; 24115RA8EC Build/AQ3A.240912.001) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.7049.79 Mobile Safari/537.36 XiaoMi/MiuiBrowser/20.3.60928
        ; Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36 EdgA/140.0.0.0
        ; Mozilla/5.0 (Windows; U; Windows NT 6.1; cs; rv:1.9.2.4) Gecko/20100513 Firefox/3.6.4 (.NET CLR 3.5.30729)
        ; Mozilla/5.0 (X11; U; Linux x86_64; en-US; rv:1.9.2.6) Gecko/20100628 Ubuntu/10.04 (lucid) Firefox/3.6.6 GTB7.0"
        ; Mozilla/5.0 (Linux; Android 9; ASUS_I005DA Build/PI; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.122 Mobile
        ; Mozilla/5.0 zgrab/0.x
        (re-find #"(?i)(?<=^Mozilla/.*)\w+(?=/[a-z0-9.]+(?: \([^\)]+\)| Mobile| GTB[0-9.]+)*$)" user-agent)

        ;; Before " feed-id:"
        ; Feedbin feed-id:1373711 - 192 subscribers
        (re-find #"(?i)^[\w\.\-_@ ]*[\w\.\-_@](?= feed-id:)" user-agent)

        ;; Before " - "
        ; Unread RSS Reader - https://www.goldenhillsoftware.com/unread/
        ; NewsBlur Feed Fetcher - 54 subscribers - https://www.newsblur.com/site/6865328/grumpy-website ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/14.0.1 Safari/605.1.15")
        (re-find #"^[\w\._@ ]*[\w\._@](?= - )" user-agent)

        ;; Before version number
        ; Emacs Elfeed 3.4.2
        ; davefeedread v0.5.25
        ; Pleroma 2.5.52-235-g589301ce-develop; https://veenus.art <lottevmusic@outlook.com>; Bot
        (re-find #"(?i)^[\w\.\-_@ ]*[\w\.\-_@](?=[- ]v?\d+\.\d+)" user-agent)

        ;; Before slash, paren, colon
        ; Tiny Tiny RSS/23.05-a4543de (Unsupported) (https://tt-rss.org/)
        ; SpaceCowboys Android RSS Reader / 2.13.0(3764)
        ; NetNewsWire (RSS Reader; https://netnewswire.com/)
        ; Fiery%20Feeds/572 CFNetwork/3860.100.1 Darwin/25.0.0
        (re-find #"(?i)^(?!mozilla)[\w\.\-_@% ]*[\w\.\-_@%](?= ?[/\(:\+])" user-agent)

        ;; Single-word
        ; node
        ; Laminas_Http_Client
        ; graphql-rss-parser
        ; Chrome Privacy Preserving Prefetch Proxy
        ; riviera golang
        ; help@dataminr.com
        (re-find #"^[\w\.\-_@ ]*[\w\.\-_@]$" user-agent)))))

(defn line-type [{:keys [path agent user-agent]}]
  (cond
    (and user-agent (re-find #"(?i)rss" user-agent))
    "feed"

    (#{"Chrome" "Firefox" "Edg" "EdgA" "EdgiOS" "Safari" "OPR" "YaBrowser" "Vivaldi" "SamsungBrowser" "UCBrowser"} agent)
    "browser"

    (and user-agent
      (re-find #"(?i)bot|crawl|fetch|node|ruby|.rb|python|curl|okhttp|spider|scan|nutch|mastodon|\+http" user-agent))
    "bot"

    (and user-agent (re-find #"^Mozilla/" user-agent))
    "browser"

    :else
    "bot"))

(defn line-os [{:keys [user-agent]}]
  (when user-agent
    (cond
      (re-find #"(?i)Android" user-agent)
      "Android"

      (re-find #"(?i)Windows" user-agent)
      "Windows"

      (re-find #"(?i)iOS|iPhone|iPad|Mobile.*Safari" user-agent)
      "iOS"

      (re-find #"(?i)macOS|Mac OS|Macintosh|Darwin" user-agent)
      "macOS"

      (re-find #"(?i)Linux|X11" user-agent)
      "Linux")))

(defn line-multiplier [{:keys [mult user-agent]}]
  (or
    (when user-agent
      (when-some [n (re-find #"\d+(?= subscriber)" user-agent)]
        (parse-long n)))
    1))

(defn long-hash ^UUID [^String s]
  (let [md (MessageDigest/getInstance "SHA-256")
        bs (.digest md (.getBytes s))]
    (UUID.
      (.getLong (ByteBuffer/wrap bs 0 8))
      (.getLong (ByteBuffer/wrap bs 8 16)))))

(defn line-uniq ^UUID [{:keys [ip user-agent agent]}]
  (long-hash
    (cond
      (and user-agent agent (re-find #"feed-id[=:]" user-agent))
      (str agent "/" (re-find #"(?<=feed-id[=:])\w+" user-agent))

      (and user-agent agent (re-find #"\d+(?= subscriber)" user-agent))
      agent

      :else
      (str ip user-agent))))

(defn line-ref-domain [{:keys [referrer]}]
  (when referrer
    (try
      (-> (java.net.URI. referrer)
        (.getHost)
        (str/replace #"^www\." ""))
      (catch Exception _))))

(defn assoc-new [m k v]
  (if (some? (get m k))
    m
    (assoc m k v)))

(defn analyze [line]
  (as-> line %
    (assoc-new % :agent      (line-agent %))
    (assoc-new % :type       (line-type %))
    (assoc-new % :os         (line-os %))
    (assoc-new % :mult       (line-multiplier %))
    (assoc-new % :uniq       (line-uniq %))
    (assoc-new % :ref-domain (line-ref-domain %))))
