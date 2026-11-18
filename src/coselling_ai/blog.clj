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
   posts are written by an AI assistant, so they are untrusted input.

   Images follow Agents of Mind's essays: first-party files under /assets/
   (deployed before the post that uses them) or an https URL. A body image on
   its own line, `![alt](src \"caption\")`, becomes a <figure> with that caption."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (org.commonmark.node Image Node)
           (org.commonmark.parser Parser)
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

;; --- Images ---

(defn image-src?
  "A first-party site path under /assets/, an uploaded image at /files/<id>
   (MoM's file store, via gm-lib uploads), or an https URL."
  [s]
  (and (string? s)
       (or (some? (re-matches #"/assets/[A-Za-z0-9._~/-]+" s))
           (some? (re-matches #"/files/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" s))
           (some? (re-matches #"https://[^\s\"'<>]+" s)))
       (not (str/includes? s ".."))))

(defn- node-seq [^Node node]
  (lazy-seq
   (when node
     (cons node (concat (node-seq (.getFirstChild node)) (node-seq (.getNext node)))))))

(defn body-images
  "Every image in a Markdown body, as {:src :alt :caption}."
  [markdown]
  (for [node (node-seq (.parse markdown-parser (str markdown)))
        :when (instance? Image node)
        :let [^Image image node]]
    {:src (.getDestination image)
     :alt (str/join (keep #(when (instance? org.commonmark.node.Text %) (.getLiteral ^org.commonmark.node.Text %))
                          (node-seq (.getFirstChild image))))
     :caption (.getTitle image)}))

(defn absolute-url
  "`src` as an absolute URL: site paths resolve against `base`."
  [base src]
  (if (str/starts-with? src "/") (str base src) src))

(defn- figures
  "An image alone on its line renders as <p><img …></p>; make it a figure, its
   Markdown title the caption. Safe to do on the HTML: raw HTML in the source is
   escaped, so every <img> here was produced by the renderer."
  [html]
  (-> html
      (str/replace #"<p><img src=\"([^\"]*)\" alt=\"([^\"]*)\" title=\"([^\"]*)\" /></p>"
                   "<figure><img src=\"$1\" alt=\"$2\" loading=\"lazy\"><figcaption>$3</figcaption></figure>")
      (str/replace #"<p><img src=\"([^\"]*)\" alt=\"([^\"]*)\" /></p>"
                   "<figure><img src=\"$1\" alt=\"$2\" loading=\"lazy\"></figure>")
      (str/replace #"<img (?![^>]*loading=)" "<img loading=\"lazy\" ")))

(defn markdown->html [s]
  (figures (.render markdown-renderer (.parse markdown-parser (str s)))))

(def ^:private date-format
  (DateTimeFormatter/ofPattern "MMMM d, yyyy" Locale/US))

(defn- published-date [millis]
  (when (number? millis)
    (.format date-format (.atZone (Instant/ofEpochMilli (long millis)) (ZoneId/of "America/Los_Angeles")))))

;; Feed items record the author's player handle; a byline shows their name.
;; Add a new author's handle here.
(def ^:private author-names {"amit" "Amit Rathore"})

(defn- author-name [handle]
  (let [handle (some-> handle name)]
    (get author-names handle handle)))

(defn byline
  "The post's byline, HTML-escaped: the author's name, or the brand when a
   staff post has no author we can name."
  [post]
  (let [kind (some-> (:byline-kind post) name)
        author (author-name (:author post))]
    (escape
     (cond
       (= "guest" kind) (str "Guest post by " (or author "a guest"))
       (contains? author-names (some-> (:author post) name)) (str "By " author)
       :else "By Coselling.ai"))))

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
           :image (some->> featured_image (absolute-url base)) :type "article"}
          (str "<main id=\"content\"><header class=\"inner-hero\">"
               "<p class=\"eyebrow\"><a href=\"/pages/blog/\">Blog</a></p>"
               "<h1>" (escape title) "</h1>"
               "<p class=\"blog-meta\">" (byline post) (when date (str " · " date)) "</p>"
               "</header><article class=\"legacy-content blog-post\">"
               (when featured_image
                 (str "<img class=\"blog-hero\" src=\"" (escape featured_image) "\" alt=\"\">"))
               (markdown->html body)
               "</article></main>"))))

;; The Welcome post predates Attention; it stays a hand-edited page and is
;; listed as the oldest entry.
(def ^:private welcome
  {:href "/pages/welcome-to-the-coselling-blog/"
   :title "Welcome to the Coselling Blog"
   :byline "By Coselling.ai"
   :summary "Coselling.ai democratizes social commerce on the internet to help businesses, creators, communities, and publishers grow by leveraging organic conversation."})

(defn- list-entry
  "`byline` is already escaped; it comes from `byline` or a literal."
  [{:keys [href title summary date image byline]}]
  (str "<li" (when image " class=\"has-thumb\"") ">"
       (when image
         (str "<a class=\"blog-thumb\" href=\"" (escape href) "\" tabindex=\"-1\" aria-hidden=\"true\">"
              "<img src=\"" (escape image) "\" alt=\"\" loading=\"lazy\"></a>"))
       "<div><a href=\"" (escape href) "\"><h2>" (escape title) "</h2></a>"
       (when (or byline date)
         (str "<p class=\"blog-meta\">" (str/join " · " (remove nil? [byline date])) "</p>"))
       "<p>" (escape summary) "</p></div></li>"))

(defn index-section
  "The post list, inserted into the static blog page after its intro."
  []
  (str "<section class=\"legacy-content blog-list\" aria-label=\"Posts\"><ul>"
       (apply str (map (fn [post]
                         (list-entry {:href (str "/pages/blog/" (:slug post) "/")
                                      :title (get-in post [:payload :title])
                                      :summary (get-in post [:payload :summary])
                                      :image (get-in post [:payload :featured_image])
                                      :byline (byline post)
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
