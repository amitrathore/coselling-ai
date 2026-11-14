(ns coselling-ai.blog
  "The blog: Attention's published feed, rendered in the site's own chrome.

   An approved Attention task becomes a feed item; this serves it at
   /pages/blog/<slug>/ and lists it on /pages/blog/. The GM keeps the published
   feed in memory, refreshed from MoM by `start!`, so a page view never waits on
   MoM. A post shows up within one refresh of being approved, and nothing is
   deployed to publish one.

   The chrome (header, nav, footer, script.js) is the hand-edited Welcome post's,
   with its relative links made root-relative so it works one level deeper. The
   body is Markdown, rendered with raw HTML escaped and unsafe URLs dropped:
   posts are written by an AI assistant, so they are untrusted input."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (org.commonmark.parser Parser)
           (org.commonmark.renderer.html HtmlRenderer)
           (java.time Instant ZoneId)
           (java.time.format DateTimeFormatter)
           (java.util Locale)))

;; --- Feed cache ---

(defonce ^:private feed (atom {:posts [] :loaded? false}))

(defn- published-posts
  "Feed items that can be served as posts, newest first."
  [items]
  (->> items
       (filter #(and (string? (:slug %)) (map? (:payload %))))
       (sort-by #(or (:published-at %) 0) >)
       vec))

(defn set-feed!
  "Replace the cached feed with Attention's `:feed` vector."
  [items]
  (reset! feed {:posts (published-posts items) :loaded? true}))

(defn posts [] (:posts @feed))

(defn find-post [slug]
  (first (filter #(= slug (:slug %)) (posts))))

(defonce ^:private worker (atom nil))

(defn start!
  "Every `interval-ms`, call `refresh!` — which loads state and returns nothing
   useful — keeping the last good feed when it throws."
  [interval-ms refresh!]
  (when (nil? @worker)
    (reset! worker
            (future
              (loop []
                (try (refresh!)
                     (catch Exception e
                       (println " [WARN] Blog refresh failed; keeping the last feed:" (.getMessage e))))
                (Thread/sleep interval-ms)
                (recur))))))

;; --- Rendering helpers ---

(defn escape [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&#39;")))

(def ^:private markdown-parser (.build (Parser/builder)))
(def ^:private markdown-renderer
  (-> (HtmlRenderer/builder) (.escapeHtml true) (.sanitizeUrls true) (.build)))

(defn markdown->html [s]
  (.render markdown-renderer (.parse markdown-parser (str s))))

(def ^:private date-format
  (DateTimeFormatter/ofPattern "MMMM d, yyyy" Locale/US))

(defn- published-date [millis]
  (when (number? millis)
    (.format date-format (.atZone (Instant/ofEpochMilli (long millis)) (ZoneId/of "America/Los_Angeles")))))

(defn- byline [post]
  (if (= "guest" (str (:byline-kind post)))
    (str "Guest post by " (escape (:author post)))
    "Coselling.ai"))

;; --- Page chrome ---

(defn- template []
  (-> (slurp (io/resource "site/pages/welcome-to-the-coselling-blog/index.html"))
      (str/replace "\"../../" "\"/")))

(defn- page
  "The site chrome around `main-html`, with this page's head metadata."
  [{:keys [title description url image type]} main-html]
  (let [t (template)
        body-start (str/index-of t "<body")
        main-start (str/index-of t "<main")
        main-end (+ (str/index-of t "</main>") (count "</main>"))
        image (or image "https://coselling.ai/assets/images/og-coselling-network.png")
        head (str "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">"
                  "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                  "<title>" (escape title) " - Coselling.ai</title>"
                  "<meta name=\"description\" content=\"" (escape description) "\">"
                  "<link rel=\"canonical\" href=\"" (escape url) "\">"
                  "<meta property=\"og:type\" content=\"" (or type "website") "\">"
                  "<meta property=\"og:title\" content=\"" (escape title) "\">"
                  "<meta property=\"og:description\" content=\"" (escape description) "\">"
                  "<meta property=\"og:url\" content=\"" (escape url) "\">"
                  "<meta property=\"og:image\" content=\"" (escape image) "\">"
                  "<meta name=\"twitter:card\" content=\"summary_large_image\">"
                  "<meta name=\"twitter:title\" content=\"" (escape title) "\">"
                  "<meta name=\"twitter:description\" content=\"" (escape description) "\">"
                  "<meta name=\"twitter:image\" content=\"" (escape image) "\">"
                  "<link rel=\"icon\" href=\"/assets/images/coselling-favicon.svg\" type=\"image/svg+xml\">"
                  "<link rel=\"stylesheet\" href=\"/styles.css?v=blog1\"></head>\n")]
    (str head
         (subs t body-start main-start)
         main-html
         (subs t main-end))))

(defn- html-response [status html]
  {:status status
   :headers {"Content-Type" "text/html; charset=utf-8" "Cache-Control" "no-cache"}
   :body html})

;; --- Pages ---

(defn post-url [base slug] (str base "/pages/blog/" slug "/"))

(defn post-page [base post]
  (let [{:keys [title summary body featured_image]} (:payload post)
        date (published-date (:published-at post))]
    (page {:title title :description summary :url (post-url base (:slug post))
           :image featured_image :type "article"}
          (str "<main id=\"content\"><header class=\"inner-hero\">"
               "<p class=\"eyebrow\"><a href=\"/pages/blog/\">Blog</a></p>"
               "<h1>" (escape title) "</h1>"
               "<p class=\"blog-meta\">" (byline post) (when date (str " · " date)) "</p>"
               "</header><article class=\"legacy-content blog-post\">"
               (when featured_image
                 (str "<img src=\"" (escape featured_image) "\" alt=\"\">"))
               (markdown->html body)
               "</article></main>"))))

;; The Welcome post predates Attention; it stays a hand-edited page and is
;; listed as the oldest entry.
(def ^:private welcome
  {:href "/pages/welcome-to-the-coselling-blog/"
   :title "Welcome to the Coselling Blog"
   :summary "Coselling.ai democratizes social commerce on the internet to help businesses, creators, communities, and publishers grow by leveraging organic conversation."})

(defn- list-entry [{:keys [href title summary date]}]
  (str "<li><a href=\"" (escape href) "\"><h2>" (escape title) "</h2></a>"
       (when date (str "<p class=\"blog-meta\">" date "</p>"))
       "<p>" (escape summary) "</p></li>"))

(defn index-section
  "The post list, inserted into the static blog page after its intro."
  []
  (str "<section class=\"legacy-content blog-list\" aria-label=\"Posts\"><ul>"
       (apply str (map (fn [post]
                         (list-entry {:href (str "/pages/blog/" (:slug post) "/")
                                      :title (get-in post [:payload :title])
                                      :summary (get-in post [:payload :summary])
                                      :date (published-date (:published-at post))}))
                       (posts)))
       (list-entry welcome)
       "</ul></section>"))

(defn index-page []
  (let [static (slurp (io/resource "site/pages/blog/index.html"))
        at (str/index-of static "</main>")]
    (str (subs static 0 at) (index-section) (subs static at))))

(defn index-response [] (html-response 200 (index-page)))

(defn post-response
  "The post at `slug`, or nil when there is none."
  [base slug]
  (when-let [post (find-post slug)]
    (html-response 200 (post-page base post))))
