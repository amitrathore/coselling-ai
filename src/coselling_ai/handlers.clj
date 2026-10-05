(ns coselling-ai.handlers
  "HTTP surface for the Coselling.ai Game Master.

   - `/v1/gm/handle` — the ONLY route MoM calls. Everything protocol-shaped
     arrives through it and is dispatched against `merged-manifest`.
   - identity + visitor routes — served to a browser, proxying MoM. The logic is
     gm-lib's (`game.gm.identity`, `game.gm.visitor`), shared with the other GMs.
   - `/ui/*` — read-only projections for the site.
   - everything else — the Coselling.ai site itself, served unchanged from
     `resources/site/` at the URLs it had on GitHub Pages (`/pages/<slug>/`).

   - `/api/market/*` and `/webhooks/stripe` — the payments boundary
     (`coselling-ai.marketplace`), ported from agents-of-mind."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [compojure.core :refer [defroutes GET POST OPTIONS]]
            [compojure.route :as route]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [ring.middleware.json :as json-middleware]
            [ring.middleware.cookies :as cookies]
            [ring.util.mime-type :as mime]
            [game.gm.config :as gm-config]
            [game.gm.dispatch :as gm-dispatch]
            [game.gm.identity :as gm-identity]
            [game.gm.runtime :as gm-runtime]
            [game.gm.visitor :as gm-visitor]
            [game.protocols.attention :as attention]
            [game.protocols.avatar :as avatar]
            [game.protocols.coseller :as coseller]
            [game.protocols.introspection :as introspection]
            [game.protocols.market :as market]
            [game.protocols.withdrawal :as withdrawal]
            [coselling-ai.marketplace :as marketplace]
            [coselling-ai.spec :as spec]))

;; --- Logging ---

(defn- log! [level msg & [data]]
  (if data
    (println (str " [" level "] " msg) (pr-str data))
    (println (str " [" level "] " msg))))

;; --- MoM plumbing ---

(defn- current-game-name []
  (some-> @gm-runtime/game-name-handle str))

(defn- submit-ui-event
  "Dispatch an event to MoM as the CALLER, forwarding their token so MoM
   re-verifies the human rather than trusting this backend."
  [request game-name seat]
  (let [body (or (:body request) {})
        roles (vec (or (:player-roles seat)
                       (some-> (:player-role seat) vector)
                       []))
        event-data (-> (or (:event/data body) {})
                       (assoc :player-name (:player-name seat))
                       (cond-> (seq roles)
                         (assoc :roles (mapv str roles))))
        payload {:event/type (str (:event/type body))
                 :event/data event-data
                 :game-name (str game-name)}]
    (http/post (str (gm-identity/mom-url nil) "/api/v1/action")
               (gm-identity/request-user-auth
                 request
                 {:content-type :json
                  :body (json/generate-string payload)
                  :as :json
                  :throw-exceptions false}))))

;; --- GM lifecycle handlers ---

(defn- handle-player-join
  "Acknowledges a join and records the member signup for referral attribution.
   If they arrived through a coseller's ?ref= link — including anonymously, via
   a visitor id later claimed — that first touch becomes the referral edge."
  [payload]
  (let [player-id   (get-in payload [:event/data :player-id])
        player-role (get-in payload [:event/data :player-role])
        signup      (coseller/handle-player-join payload)]
    (cond-> {:success true}
      player-id   (assoc :player-id (str player-id))
      player-role (assoc :player-role (str player-role))
      (:updated-state signup) (assoc :updated-state (:updated-state signup))
      (seq (:events signup))  (assoc :events (:events signup)))))

(def gm-manifest
  {:gm {:player-join
        {:handler handle-player-join
         :type :lifecycle
         :doc (str "Acknowledges a player join and records the member signup for "
                   "referral attribution — if they arrived via a coseller's ?ref= "
                   "link, that first touch becomes the referral edge.")
         :summary "Confirm the player role on join and record the signup."
         :when-to-use "Fired by MoM when a player claims a seat. Not called directly."
         :roles #{:any}
         :input [:map]
         :output [:map [:player-role {:optional true} :string]]
         :errors []
         :examples [{:request {:event/type "gm/player-join"
                               :event/data {:player-name "ari" :player-role "audience"}}
                     :response {:success true :player-role "audience"}}]}

        ;; MoM's GET /admin/games/<name>/state routes gm/query-state through the
        ;; GM; gm-lib supplies no handler, so without this that route 400s.
        :query-state
        {:handler (fn [payload] {:success true :data {:game-state (:game-state payload)}})
         :type :query
         :doc "Returns the full game-state snapshot exactly as provided in webhook context."
         :summary "Read current materialized state."
         :when-to-use "Diagnostics, UI hydration, external observer sync."
         :roles #{:any}
         :input [:map]
         :output [:map [:data [:map [:game-state :map]]]]
         :errors []
         :examples [{:request {:event/type "gm/query-state" :event/data {}}
                     :response {:success true :data {:game-state {}}}}]}}})

;; --- Manifest ---
;;
;; Every protocol Coselling.ai could dispatch. Only names in the spec's :protocols end
;; up in merged-manifest, so this may be wider than the spec — never narrower.
;; market and coseller are registered WHOLE: Coselling.ai settles real sales.

(def protocol-registry
  {"attention"     attention/manifest
   "market"        market/manifest
   "coseller"      coseller/manifest
   "withdrawal"    withdrawal/manifest
   "introspection" introspection/manifest
   "avatar"        avatar/manifest})

(def merged-manifest
  (let [names   (:protocols spec/game-spec)
        unknown (remove protocol-registry names)]
    (when (seq unknown)
      (println " [GM] WARNING: unknown protocols in game-spec:" (vec unknown)))
    (merge (apply merge (keep protocol-registry names))
           gm-manifest)))

;; --- Dispatcher ---

(defn dispatch [payload]
  (let [event-type (:event/type payload)]
    (log! "INFO" "Event received" {:event/type event-type})
    (let [result (gm-dispatch/dispatch-gm-handler payload merged-manifest)]
      (if (:success result)
        (log! "INFO" "Event handled"
              {:event/type event-type
               :status (or (:status result) 200)
               :updated-state-keys (->> (keys (or (:updated-state result) {}))
                                        (map name) sort vec)})
        (log! "WARN" "Event failed"
              {:event/type event-type :error (:error result)}))
      result)))

(defn- mom-webhook-authorized?
  [request]
  (let [expected (gm-config/resolve-mom-api-token)
        presented (some-> (gm-identity/auth-header (:headers request))
                          (str/replace #"^Bearer " ""))]
    (or (str/blank? (str expected))
        (= (str expected) (str presented)))))

;; --- Public read model ---

(defn- with-service-auth [req]
  (if-let [headers (gm-config/mom-auth-headers)]
    (assoc req :headers headers)
    req))

(defn- fetch-game-state
  "Durable game state from MoM. Service auth when the GM holds a credential,
   otherwise the caller's — an anonymous visitor still needs to see offers."
  [request game-name]
  (try
    (let [base {:as :json :throw-exceptions false}
          req (if (gm-config/resolve-mom-game-token)
                (with-service-auth base)
                (gm-identity/request-user-auth request base))
          resp (http/get (str (gm-identity/mom-url nil)
                              "/api/v1/storage/game/" game-name "/reload")
                         req)]
      (:body resp))
    (catch Exception e
      (log! "ERROR" "UI state reload failed" {:error (.getMessage e)})
      nil)))

(defn overlay-state
  "Merge durable state onto the seeded initial state so a sparse or
   freshly-created game still renders its seeded content. nil in the durable
   state means 'missing', not 'empty'."
  [seeded durable]
  (cond
    (nil? durable) seeded
    (and (map? seeded) (map? durable))
    (reduce-kv (fn [m k v] (assoc m k (overlay-state (get seeded k) v)))
               seeded durable)
    :else durable))

(defn- effective-state
  [request game-name]
  (let [state (fetch-game-state request game-name)
        payload (if (and (map? state) (:success state) (contains? state :state))
                  (:state state)
                  state)]
    (overlay-state (:initial-state spec/game-spec) payload)))

(defn public-game-state
  "The anonymous read model: published content and the offer catalog only.
   Never referral policy, signups, touches, orders or audit trails — cosellers
   and partners get scoped views, not the whole state (§15)."
  [effective]
  {:attention (select-keys (:attention effective) [:brand-bible :feed])
   :market {:capabilities (into {}
                               (map (fn [[id capability]]
                                      [id (select-keys capability
                                                       [:id :seller :title :description :kind
                                                        :external-ref :terms :commerce
                                                        :entitlements :category-ids
                                                        :hashtag-ids :images :checkout
                                                        :status :display-order
                                                        :created-at :updated-at])]))
                               (get-in effective [:market :capabilities] {}))
            :categories (get-in effective [:market :categories] {})
            :hashtags (get-in effective [:market :hashtags] {})}})

;; --- Identity ---

(defn- identity-opts []
  {:game-id (some-> @gm-runtime/game-id str)
   :game-name (current-game-name)
   :app-base-url (gm-config/resolve-app-base-url)
   ;; Attention role names, not display labels: strategist runs the network.
   :gated-roles #{"strategist" "creator"}
   ;; Required because there is no :ensure-membership-fn: without it a person
   ;; who owns exactly one seat gets selectedSeat nil and is asked to claim a
   ;; handle they already have.
   :select-sole-seat? true})
   ;; DELIBERATELY no :ensure-membership-fn. It would enrol a signed-in visitor
   ;; under a generated handle, and MoM has no rename — player-name is the join
   ;; key across protocol state and the ledger. /join/claim lets them choose.

(defn- seat-roles [seat]
  (set (map str (or (:player-roles seat)
                    (some-> (:player-role seat) vector)
                    []))))

(defn- result-body
  [response]
  (let [body (:body response)]
    (or (:gm-result body) (get body "gm-result") body)))

(defn- activation-response
  "Opt a seated person into Coselling: claim the coseller role if they lack it,
   then register them with the Coseller Protocol to mint their ref token."
  [request context seat]
  (let [roles (seat-roles seat)
        role-response (when-not (contains? roles "coseller")
                        (gm-identity/claim-membership-response
                         (assoc request :body {:player-name (:player-name seat)
                                               :roles ["coseller"]})
                         (:game-name context)))
        role-ok? (or (nil? role-response)
                     (<= 200 (:status role-response) 299))]
    (if-not role-ok?
      role-response
      (let [coseller-seat (assoc seat
                                 :player-roles (vec (conj roles "coseller"))
                                 :player-role "coseller")
            registration (submit-ui-event
                          (assoc request :body {:event/type "coseller/register"
                                                :event/data {}})
                          (:game-name context)
                          coseller-seat)
            result (result-body registration)]
        (if (and (<= 200 (:status registration) 299)
                 (true? (or (:success result) (get result "success"))))
          {:status 200
           :body {:status "active"
                  :player-name (str (:player-name seat))
                  :ref-token (or (get-in result [:data :ref-token])
                                 (get-in result ["data" "ref-token"]))}}
          {:status (or (:status registration) 502)
           :body result})))))

;; --- The site ---
;;
;; The Coselling.ai site is served exactly as it was on GitHub Pages: the files
;; under resources/site/ at the same paths, so every existing link, canonical
;; URL and search result keeps working. These pages are the content of record,
;; hand-edited since the WordPress import — never regenerate them.
;;
;; Every page links relatively (`../../pages/brands/`), so a directory URL must
;; end in a slash or its links resolve one level too high. A bare `/pages/x`
;; redirects to `/pages/x/`, keeping the query string: `?ref=` rides on it.

(def ^:private site-root "site")

(def ^:private mime-types
  ;; ring's table predates these.
  {"webp" "image/webp" "avif" "image/avif" "svg" "image/svg+xml"
   "webmanifest" "application/manifest+json"})

(defn- site-resource
  "The classpath resource for a site FILE path, or nil. io/resource also
   answers for directories (from the filesystem and from a jar), so only a
   path whose last segment has an extension counts — every site file has one."
  [path]
  (when (and (not (str/includes? path ".."))
             (re-find #"/[^/]+\.[A-Za-z0-9]+$" path))
    (io/resource (str site-root path))))

(defn- uncached? [path]
  ;; Pages and script.js carry no version in their URL, and styles.css's ?v=
  ;; differs page to page (v=11 on most), so a cached copy would strand people
  ;; on an old build. Revalidating is cheap; images may be cached.
  (some #(str/ends-with? path %) [".html" ".js" ".css"]))

(defn- file-response [status path resource]
  (let [type (or (mime/ext-mime-type path mime-types) "application/octet-stream")]
    {:status status
     :headers {"Content-Type" (if (str/starts-with? type "text/")
                                (str type "; charset=utf-8")
                                type)
               "Cache-Control" (if (uncached? path)
                                 "no-cache"
                                 "public, max-age=86400")}
     :body (io/input-stream resource)}))

(defn not-found-response []
  (if-let [page (site-resource "/404.html")]
    (file-response 404 "/404.html" page)
    {:status 404 :body {:error "Not found"}}))

(defn site-response
  "Serve a site file for a GET, or nil when the path is not part of the site."
  [{:keys [uri query-string]}]
  (let [uri (or uri "/")]
    (cond
      (str/ends-with? uri "/")
      (when-let [page (site-resource (str uri "index.html"))]
        (file-response 200 (str uri "index.html") page))

      (site-resource uri)
      (file-response 200 uri (site-resource uri))

      (site-resource (str uri "/index.html"))
      {:status 301
       :headers {"Location" (cond-> (str uri "/")
                              (not (str/blank? query-string)) (str "?" query-string))}
       :body ""})))

(defn- alias-redirect [{:keys [query-string]} target]
  {:status 302
   :headers {"Location" (cond-> target
                          (not (str/blank? query-string)) (str "?" query-string))}
   :body ""})

;; --- Coseller self-view ---

(defn- state-entry
  "Case-insensitive lookup: game-state JSON keywordizes dynamic record keys."
  [m id]
  (some (fn [[k v]] (when (= (str/lower-case (name k)) (str/lower-case (str id))) v))
        (or m {})))

(defn coseller-self
  "What a coseller may see about their own standing: their ref token and the
   published policy. Nothing about anyone else."
  [effective player-name]
  (let [coseller (state-entry (get-in effective [:coseller :cosellers]) player-name)
        policy (get-in effective [:coseller :policy])]
    {:status (if coseller "active" "needs-registration")
     :player-name (str player-name)
     :ref-token (:ref-token coseller)
     :policy (select-keys policy [:algorithm :window-days :payout-hold-days
                                  :pool-share-bps :referral-override-bps])}))

(defn- session-context [request]
  (gm-identity/session-context request (identity-opts)))

(def ^:private cors-json-post
  {"Access-Control-Allow-Origin" "*"
   "Access-Control-Allow-Headers" "Content-Type, Authorization, X-Selected-Seat"
   "Access-Control-Allow-Methods" "POST, OPTIONS"})

(defroutes gm-routes
  (GET "/health" [] {:status 200 :body {:ok true :game (current-game-name)}})

  (GET "/auth.json" [:as request]
    (gm-identity/auth-config-response request (identity-opts)))
  (GET "/auth/callback" [:as request]
    (gm-identity/ui-redirect-with-query request (identity-opts)))
  (GET "/auth/success" [:as request]
    (gm-identity/ui-redirect-with-query request (identity-opts)))
  (GET "/auth/failure" [:as request]
    (gm-identity/ui-redirect-with-query request (identity-opts)))

  ;; The hosted auth app POSTs to the GM's own origin, never to MoM directly.
  ;; Missing these shows up as "Failed to fetch (status=network)".
  (OPTIONS "/auth/login" []
    (gm-identity/cors-preflight-response))
  (POST "/auth/login" [:as request]
    (gm-identity/login-response request))
  (GET "/auth/me" [:as request]
    (gm-identity/auth-response request))

  ;; Enrol under a name the person chose; must run as them.
  (OPTIONS "/join/claim" []
    (gm-identity/cors-preflight-response))
  (POST "/join/claim" [:as request]
    (gm-identity/claim-membership-response request (current-game-name)))

  (GET "/session" [:as request]
    {:status 200 :body (gm-identity/session-response-body (session-context request))})

  (GET "/seats" [:as request]
    (let [{:keys [context response]} (gm-identity/require-game-context request (identity-opts))]
      (or response
          {:status 200
           :body {:gameId (:game-id context)
                  :selectedSeat (gm-identity/seat-summary (:selected-seat context))
                  :seats (mapv gm-identity/seat-summary (:seats context))}})))

  ;; --- Anonymous visitors ---
  ;;
  ;; Every page carrying ?ref= is an attributable surface; a shared offer link
  ;; records a visitor touch the person can claim after signing in.
  ;; `wrap-cookies` below is load-bearing: without it every visit mints a fresh
  ;; visitor id and nothing is ever claimable, while every call still returns 200.
  (OPTIONS "/public/touch" []
    {:status 204
     :headers {"Access-Control-Allow-Origin" "*"
               "Access-Control-Allow-Headers" "Content-Type"
               "Access-Control-Allow-Methods" "POST, OPTIONS"}})
  (POST "/public/touch" [:as request]
    (gm-visitor/record-visitor-touch request))
  (POST "/coseller/claim-visitor" [:as request]
    (let [{:keys [context seat response]} (gm-identity/require-selected-seat request (identity-opts))]
      (or response
          (gm-visitor/claim-visitor-touches request (:game-name context) seat
                                            {:submit-ui-event-fn submit-ui-event}))))

  (POST "/api/coseller/activate" [:as request]
    (let [{:keys [context seat response]}
          (gm-identity/require-selected-seat request (identity-opts))]
      (or response (activation-response request context seat))))

  (GET "/api/coseller/me" [:as request]
    (let [{:keys [context seat response]} (gm-identity/require-selected-seat request (identity-opts))]
      (cond
        response response
        (not (contains? (seat-roles seat) "coseller"))
        {:status 403 :body {:error {:code "coseller-role-required"
                                    :message "Activate recommending to get your link."}}}
        :else
        (if-let [state (effective-state request (:game-name context))]
          {:status 200 :body (coseller-self state (:player-name seat))}
          {:status 502 :body {:error {:code "state-unavailable"}}}))))

  (OPTIONS "/events" []
    {:status 204 :headers cors-json-post})
  (POST "/events" [:as request]
    (let [{:keys [context seat response]} (gm-identity/require-selected-seat request (identity-opts))]
      (or response
          (let [resp (submit-ui-event request (:game-name context) seat)]
            {:status (:status resp) :body (:body resp)}))))

  (POST "/v1/gm/handle" [:as request]
    (if-not (mom-webhook-authorized? request)
      {:status 401
       :body {:success false
              :error {:code "untrusted_mom_envelope"
                      :message "The GM webhook requires MoM authentication."}}}
      (let [body (:body request)
            result (dispatch (if (map? body) body {}))]
        {:status (or (:status result) 200)
         :body (dissoc result :status)})))

  marketplace/routes

  (GET "/ui/public-state" [:as request]
    (let [gname (current-game-name)
          gid (some-> @gm-runtime/game-id str)]
      (if (str/blank? (str gname))
        {:status 400 :body {:error {:code "missing-game-name"
                                    :message "GAME_NAME not set or registration incomplete"}}}
        (if-let [state (effective-state request gname)]
          {:status 200 :body {:gameId gid :gameState (public-game-state state)}}
          {:status 502 :body {:error {:code "state-unavailable"
                                      :message "Failed to load durable game state from MoM"}}}))))

  ;; --- The site ---
  ;;
  ;; Short names for the coseller pages, for links people type or say aloud.
  ;; The query string survives: it is how ?ref= reaches the page.
  (GET "/join" [:as request] (alias-redirect request "/pages/join/"))
  (GET "/share" [:as request] (alias-redirect request "/pages/share/"))

  ;; Anything else is a site file or the site's 404 page. Every page carries
  ;; ?ref= attribution (script.js records it), so a shared link to any of them
  ;; must survive a cold load.
  (GET "*" [:as request] (or (site-response request) (not-found-response)))

  (route/not-found {:status 404 :body {:error "Not found"}}))

(defn- wrap-canonical-host
  "Send www.<site> to the site's own origin (APP_BASE_URL), path and query
   intact, as GitHub Pages did. Sign-in returns to APP_BASE_URL, so a session
   started on www would otherwise land on a different origin. Every other host
   passes through: MoM calls the GM at its fly.dev name."
  [handler]
  (fn [{:keys [uri query-string headers] :as request}]
    (let [base (some-> (gm-config/resolve-app-base-url) (str/replace #"/+$" ""))
          site-host (some-> base (str/replace #"^https?://" ""))
          host (some-> (get headers "host") str/lower-case (str/replace #":\d+$" ""))]
      (if (and site-host (= host (str "www." site-host)))
        {:status 301
         :headers {"Location" (cond-> (str base uri)
                                (not (str/blank? query-string)) (str "?" query-string))}
         :body ""}
        (handler request)))))

(def app
  (-> gm-routes
      (wrap-canonical-host)
      (json-middleware/wrap-json-body {:keywords? true})
      (marketplace/wrap-payment-readiness)
      ;; Stripe signs the exact bytes, so they are captured before the JSON
      ;; middleware consumes the body.
      (marketplace/wrap-stripe-raw-body)
      ;; Anonymous visitor ids ride in a cookie so ?ref= history survives the
      ;; full navigation to the auth app, which destroys page state.
      (cookies/wrap-cookies)
      (json-middleware/wrap-json-response)))
