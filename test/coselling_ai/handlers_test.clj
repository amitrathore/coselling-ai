(ns coselling-ai.handlers-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [game.gm.config :as gm-config]
            [coselling-ai.handlers :as handlers]))

(defn- GET [uri & [query-string]]
  (handlers/app {:request-method :get :uri uri :query-string query-string
                 :scheme :https :headers {"host" "coselling.example"}}))

(defn- body [resp]
  (let [b (:body resp)]
    (if (instance? java.io.InputStream b) (slurp b) (str b))))

(defn- site-pages
  "Every page in resources/site, as the URL it is served at."
  []
  (let [root (io/file "resources/site")
        root-path (.getPath root)]
    (->> (file-seq root)
         (filter #(= "index.html" (.getName %)))
         (map #(-> (.getPath %)
                   (subs (count root-path))
                   (str/replace #"index\.html$" "")))
         sort)))

(deftest every-site-page-is-served-at-its-github-pages-url
  ;; Shared links and search results land on these directly; a 404 on any of
  ;; them breaks the link and the attribution riding on it.
  (let [pages (site-pages)]
    (is (< 30 (count pages)) "the whole site ships")
    (doseq [path pages]
      (let [resp (GET path)]
        (is (= 200 (:status resp)) path)
        (is (str/starts-with? (get-in resp [:headers "Content-Type"]) "text/html") path)
        (is (str/includes? (body resp) "script.js") (str path " must load the network layer"))))))

(deftest the-launch-offer-is-ported-as-is
  (let [html (body (GET "/pages/launch-your-network/"))]
    (is (str/includes? html "Launch Your Own Coseller Network"))
    (is (str/includes? html "$4,999"))
    (is (str/includes? html "$299"))))

(deftest a-directory-url-without-its-slash-keeps-the-ref
  ;; Pages link relatively, so /pages/brands must become /pages/brands/ — and
  ;; the redirect must keep ?ref=, or the share is lost on the way in.
  (let [resp (GET "/pages/brands" "ref=tok-1")]
    (is (= 301 (:status resp)))
    (is (= "/pages/brands/?ref=tok-1" (get-in resp [:headers "Location"])))))

(deftest short-coseller-urls-keep-the-ref
  (is (= "/pages/share/?ref=tok-1" (get-in (GET "/share" "ref=tok-1") [:headers "Location"])))
  (is (= "/pages/join/" (get-in (GET "/join") [:headers "Location"]))))

(deftest assets-are-served-with-their-types
  (let [css (GET "/styles.css")
        js (GET "/script.js")
        svg (GET "/assets/images/coselling-favicon.svg")]
    (is (= 200 (:status css)))
    (is (str/starts-with? (get-in css [:headers "Content-Type"]) "text/css"))
    (is (str/includes? (get-in js [:headers "Content-Type"]) "javascript"))
    (is (= "image/svg+xml" (get-in svg [:headers "Content-Type"])))
    (is (= "no-cache" (get-in js [:headers "Cache-Control"])) "unversioned: must revalidate")))

(deftest unknown-paths-get-the-site-404
  (let [resp (GET "/pages/no-such-page/")]
    (is (= 404 (:status resp)))
    (is (str/includes? (body resp) "This conversation moved."))))

(deftest paths-cannot-escape-the-site
  (doseq [path ["/../auth.json" "/pages/../../auth.json" "/%2e%2e/auth.json"]]
    (is (not= 200 (:status (GET path))) path)))

(deftest every-page-links-share-and-earn
  (doseq [path (site-pages)]
    (is (str/includes? (body (GET path)) "share/\">Share &amp; Earn</a>") path)))

(deftest coseller-pages-carry-their-panels
  (is (str/includes? (body (GET "/pages/join/")) "data-gm=\"join\""))
  (is (str/includes? (body (GET "/pages/share/")) "data-gm=\"share\"")))

(deftest coseller-self-shows-only-your-own-standing
  (let [state {:coseller {:cosellers {:mira {:ref-token "tok-m"} :jon {:ref-token "tok-j"}}
                          :touches [{:coseller "jon" :ts 1}]
                          :policy {:algorithm "30-days-linear" :referral-override-bps 500
                                   :history [{:secret true}]}}}
        me (handlers/coseller-self state "Mira")]
    (is (= "active" (:status me)))
    (is (= "tok-m" (:ref-token me)) "lookup is case-insensitive")
    (is (not (str/includes? (pr-str me) "tok-j")))
    (is (not (contains? (:policy me) :history)))
    (is (= "needs-registration" (:status (handlers/coseller-self state "nobody"))))))

(deftest gm-routes-win-over-the-site
  (is (= 200 (:status (GET "/health")))))

(deftest www-redirects-to-the-site-origin
  ;; Sign-in returns to APP_BASE_URL, so the site lives on one origin only.
  (with-redefs [gm-config/resolve-app-base-url (constantly "https://coselling.example")]
    (let [on-host (fn [host uri & [query-string]]
                    (handlers/app {:request-method :get :uri uri :query-string query-string
                                   :scheme :https :headers {"host" host}}))
          resp (on-host "www.coselling.example" "/pages/about-us/" "ref=tok-m")]
      (is (= 301 (:status resp)))
      (is (= "https://coselling.example/pages/about-us/?ref=tok-m"
             (get-in resp [:headers "Location"])))
      (is (= 200 (:status (on-host "coselling.example" "/pages/about-us/"))))
      (is (= 200 (:status (on-host "coselling-ai.fly.dev" "/health")))
          "MoM and the health check reach the GM by its fly.dev name"))))
