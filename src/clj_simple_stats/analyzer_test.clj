(ns clj-simple-stats.analyzer-test
  (:require
   [clj-simple-stats.analyzer :as analyzer]
   [clojure.string :as str]
   [clojure.test :refer [deftest is are testing]]))

(deftest test-line-agent
  (are [user-agent agent] (= agent (analyzer/line-agent {:user-agent user-agent}))
    ;; Special cases
    "Mozilla/4.0 Leed (LightFeed Aggregator) dev by idleman http://projet.idleman.fr/leed"
    "Leed"

    "Mozilla/4.0 Leed (LightFeed Agrgegator) Stable by idleman http://projet.idleman.fr/leed"
    "Leed"

    "Mozilla/5.0 (Linux; U; en-us; BeyondPod 4)"
    "BeyondPod"

    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36 Edg/140.0.0.0; 360Spider"
    "360Spider"

    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36/Nutch-1.21-SNAPSHOT"
    "Nutch"

    "Mozilla/5.0 (Windows NT 6.1; WOW64) SkypeUriPreview Preview/0.5 skype-url-preview@microsoft.com"
    "Skype"

    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4450.0 Safari/537.36 LarkUrl"
    "Lark"

    "Mozilla/5.0 (Linux; Android 12; redroid12_arm64 Build/SQ1D.220205.004; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/122.0.6261.119 Mobile Safari/537.36 uni-app"
    "uni-app"

    ;; CFNetwork
    "27F9D996-DF69-4753-A16D-76FBB0D682A3/310 CFNetwork/3826.400.120 Darwin/24.3.0"
    "CFNetwork"

    ;; After "compatible"
    "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko; compatible; bingbot/2.0; +http://www.bing.com/bingbot.htm) Chrome/116.0.1938.76 Safari/537.36"
    "bingbot"

    "Mozilla/5.0 (compatible; archive.org_bot +http://archive.org/details/archive.org_bot) Zeno/33782fc warc/v0.8.90"
    "archive.org_bot"

    "Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 5.2; Trident/5.0)"
    "MSIE 9.0"

    "Mozilla/5.0 (Linux; Android 6.0.1; Nexus 5X Build/MMB29P) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.7339.207 Mobile Safari/537.36 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"
    "Googlebot"

    "Mozilla/5.0 (Linux; Android 5.0) AppleWebKit/537.36 (KHTML, like Gecko) Mobile Safari/537.36 (compatible; TikTokSpider; ttspider-feedback@tiktok.com)"
    "TikTokSpider"

    "Mozilla/5.0 (Linux; Android 7.0;) AppleWebKit/537.36 (KHTML, like Gecko) Mobile Safari/537.36 (compatible; PetalBot;+https://webmaster.petalsearch.com/site/petalbot)"
    "PetalBot"

    ;; Before (ro)?bot
    "progresswall.com robot"
    "progresswall.com robot"

    "star-finder.de Bot"
    "star-finder.de Bot"

    "RSS Bot/2.8 (Mac OS X Version 10.14.5 (Build 18F132))"
    "RSS Bot"

    ;; Containt "bot"
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15 (Applebot/0.1; +http://www.apple.com/go/applebot)"
    "Applebot"

    "Mozilla/5.0 (keys-so-bot)"
    "keys-so-bot"

    "Slackbot-LinkExpanding 1.0 (+https://api.slack.com/robots)"
    "Slackbot"

    "StreamReader bot : http://rss.nhenry.fr/"
    "StreamReader bot"

    "RSSingBot (http://www.rssing.com)"
    "RSSingBot"

    ;; IE
    "Mozilla/5.0 (Windows NT 6.1; WOW64; Trident/7.0; PRU_IE; rv:11.0) like Gecko"
    "Trident"

    ;; Before "Mobile? Safari"
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
    "Chrome"

    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
    "Chrome"

    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.200 YaBrowser/24.1.0.0 Safari/537.36"
    "YaBrowser"

    "Mozilla/5.0 (Linux; U; Android 11; en-US; V2027) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/89.0.4389.116 UCBrowser/13.4.0.1306 Mobile Safari/537.36"
    "UCBrowser"

    "Mozilla/5.062334851 Mozilla/5.0 (Windows NT 6.3; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/68.0.3440.106 Safari/537.36"
    "Chrome"

    ;; First component
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/28.0 Chrome/130.0.0.0 Mobile Safari/537.36"
    "SamsungBrowser"

    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) EdgiOS/121.0.2277.107 Version/17.0 Mobile/15E148 Safari/604.1"
    "EdgiOS"

    ;; Last component
    "Mozilla/5.0 (iPhone; CPU iPhone OS 10_3_1 like Mac OS X) AppleWebKit/603.1.30 (KHTML, like Gecko) Version/10.0 Mobile/14E304 Safari/602.1"
    "Safari"

    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/14.0.3 Safari/605.1.15"
    "Safari"

    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0"
    "Firefox"

    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36 Edg/141.0.0.0"
    "Edg"

    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36 OPR/121.0.0.0"
    "OPR"

    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_10_3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/41.0.2272.105 Safari/537.36 Vivaldi/1.0.162.9"
    "Vivaldi"

    "Mozilla/5.0 (Linux; U; Android 15; zh-cn; 24115RA8EC Build/AQ3A.240912.001) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.7049.79 Mobile Safari/537.36 XiaoMi/MiuiBrowser/20.3.60928"
    "MiuiBrowser"

    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36 EdgA/140.0.0.0"
    "EdgA"

    "Mozilla/5.0 (Windows; U; Windows NT 6.1; cs; rv:1.9.2.4) Gecko/20100513 Firefox/3.6.4 (.NET CLR 3.5.30729)"
    "Firefox"

    "Mozilla/5.0184010163 Mozilla/5.0 (Windows NT 6.1; Win64; x64; rv:57.0) Gecko/20100101 Firefox/57.0"
    "Firefox"

    "Mozilla/5.0 (X11; U; Linux x86_64; en-US; rv:1.9.2.6) Gecko/20100628 Ubuntu/10.04 (lucid) Firefox/3.6.6 GTB7.0"
    "Firefox"

    "Mozilla/5.0 (Linux; Android 9; ASUS_I005DA Build/PI; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.122 Mobile"
    "Chrome"

    "Mozilla/5.0 zgrab/0.x"
    "zgrab"

    ;; Before "feed-id:"
    "Feedbin feed-id:1373711 - 192 subscribers"
    "Feedbin"

    ;; Before " - "
    "Unread RSS Reader - https://www.goldenhillsoftware.com/unread/"
    "Unread RSS Reader"

    "NewsBlur Feed Fetcher - 54 subscribers - https://www.newsblur.com/site/6865328/grumpy-website (\"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/14.0.1 Safari/605.1.15\")"
    "NewsBlur Feed Fetcher"

    ;; Before version number
    "Emacs Elfeed 3.4.2"
    "Emacs Elfeed"

    "davefeedread v0.5.25"
    "davefeedread"

    "Pleroma 2.5.52-235-g589301ce-develop; https://veenus.art <lottevmusic@outlook.com>; Bot"
    "Pleroma"

    ;; Before slash, paren, colon
    "Tiny Tiny RSS/23.05-a4543de (Unsupported) (https://tt-rss.org/)"
    "Tiny Tiny RSS"

    "SpaceCowboys Android RSS Reader / 2.13.0(3764)"
    "SpaceCowboys Android RSS Reader"

    "NetNewsWire (RSS Reader; https://netnewswire.com/)"
    "NetNewsWire"

    "Fiery%20Feeds/572 CFNetwork/3860.100.1 Darwin/25.0.0"
    "Fiery%20Feeds"

    ;; Single-word
    "node"
    "node"

    "Laminas_Http_Client"
    "Laminas_Http_Client"

    "graphql-rss-parser"
    "graphql-rss-parser"

    "Chrome Privacy Preserving Prefetch Proxy"
    "Chrome Privacy Preserving Prefetch Proxy"

    "riviera golang"
    "riviera golang"

    "help@dataminr.com"
    "help@dataminr.com"))
