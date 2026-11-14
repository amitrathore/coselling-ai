(ns coselling-ai.app
  "app.coselling.ai — the operator app, served by this GM on its own host.

   An operator signs in with Intergraph Login, picks a game they HOST, and sees
   its 5Cs flywheel (`coselling-ai.flywheel`). Any Intergraph game, not just
   this one: the data comes from MoM's host-scoped read, never from this GM's
   own credential.

   - GET /api/app/games            → MoM /api/v1/orgchief/owned-games
   - GET /api/app/games/:g/flywheel → MoM /api/v1/games/<g>/orgchief/snapshot,
                                      run through `flywheel/model`

   Both forward the CALLER's token, so MoM decides who hosts what: a 403 from
   the snapshot means \"you don't host this game\" and is passed through as
   that. MoM's /admin/* reads are deliberately not used — they don't check
   hosting.

   Sign-in is the site's own flow (gm-lib identity), pointed back at this host:
   browser storage is per origin, so the app holds its own session."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [compojure.core :refer [routes GET POST OPTIONS]]
            [clj-http.client :as http]
            [ring.util.codec :as codec]
            [game.gm.identity :as gm-identity]
            [coselling-ai.flywheel :as flywheel]))

(defn- log! [level msg & [data]]
  (println (str " [" level "] " msg) (if data (pr-str data) "")))

;; --- which host is the app --------------------------------------------------

(defn app-host?
  "app.coselling.ai in production; any app.* host (app.localhost:8894 resolves
   to loopback in every browser) locally. APP_HOST pins it exactly."
  [host]
  (let [host (some-> host str/lower-case (str/replace #":\d+$" ""))
        pinned (some-> (System/getenv "APP_HOST") str/trim str/lower-case not-empty)]
    (boolean (and host (if pinned (= host pinned) (str/starts-with? host "app."))))))

(defn request-host [request]
  (get-in request [:headers "host"]))

;; --- MoM reads, as the caller -----------------------------------------------

(defn- mom-get [request path]
  (http/get (str (gm-identity/mom-url nil) path)
            (gm-identity/request-user-auth
             request {:as :json :throw-exceptions false
                      :socket-timeout 15000 :connection-timeout 5000})))

(def ^:private cache-ttl-ms 30000)
(defonce ^:private snapshot-cache (atom {}))

(defn- cache-key [request game-name]
  ;; Keyed by the caller's token, so one operator's read is never served to
  ;; another. Hashed: no bearer tokens sitting in a map.
  [(hash (gm-identity/auth-header (:headers request))) game-name])

(defn- fetch-snapshot
  "{:status :body}: MoM's snapshot for a game the caller hosts, cached briefly
   per caller so flipping ranges doesn't re-read the whole game."
  [request game-name now]
  (let [k (cache-key request game-name)
        hit (get @snapshot-cache k)]
    (if (and hit (< (- now (:at hit)) cache-ttl-ms))
      (:resp hit)
      (let [resp (mom-get request (str "/api/v1/games/" game-name "/orgchief/snapshot"))
            out {:status (:status resp) :body (:body resp)}]
        (when (= 200 (:status resp))
          (swap! snapshot-cache
                 (fn [c] (-> (into {} (filter #(< (- now (:at (val %))) cache-ttl-ms)) c)
                             (assoc k {:at now :resp out})))))
        out))))

(defn- error [status code message]
  {:status status :body {:error code :message message}})

(defn- signed-in? [request]
  (some? (gm-identity/auth-header (:headers request))))

(defn games-response [request]
  (if-not (signed-in? request)
    (error 401 "sign-in-required" "Sign in to see the games you host.")
    (let [resp (mom-get request "/api/v1/orgchief/owned-games")]
      (case (:status resp)
        200 {:status 200
             :body {:games (->> (get-in resp [:body :games])
                                (mapv #(select-keys % [:id :game-name :name :description :protocols]))
                                (sort-by (comp str/lower-case str :name))
                                vec)}}
        (401 403) (error 401 "sign-in-required" "Your sign-in has expired. Sign in again.")
        (do (log! "WARN" "owned-games failed" {:status (:status resp)})
            (error 502 "mom-unavailable" "Couldn't reach Intergraph. Try again in a moment."))))))

(def ^:private game-name-pattern #"[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")

(defn flywheel-response [request game-name range-key]
  (cond
    (not (signed-in? request))
    (error 401 "sign-in-required" "Sign in to see the games you host.")

    (not (re-matches game-name-pattern (str game-name)))
    (error 400 "bad-game" "That isn't a game name.")

    :else
    (let [now (System/currentTimeMillis)
          {:keys [status body]} (fetch-snapshot request game-name now)]
      (case status
        200 {:status 200 :body (flywheel/model body now (or range-key flywheel/default-range))}
        401 (error 401 "sign-in-required" "Your sign-in has expired. Sign in again.")
        403 (error 403 "not-host" "Only the game's host can see its flywheel.")
        404 (error 404 "no-such-game" "There's no game by that name.")
        (do (log! "WARN" "snapshot failed" {:game game-name :status status})
            (error 502 "mom-unavailable" "Couldn't reach Intergraph. Try again in a moment."))))))

;; No wrap-params in this GM's stack; the app reads its one query param itself.
(defn- query-param [request k]
  (let [qs (:query-string request)
        params (when-not (str/blank? qs) (codec/form-decode qs))]
    (when (map? params) (let [v (get params k)] (if (sequential? v) (first v) v)))))

;; --- the shell ----------------------------------------------------------------

(def ^:private app-files
  {"/" ["app/index.html" "text/html; charset=utf-8"]
   "/app.js" ["app/app.js" "application/javascript; charset=utf-8"]
   "/app.css" ["app/app.css" "text/css; charset=utf-8"]})

(defn- file-response [uri]
  (when-let [[path type] (get app-files uri)]
    (when-let [res (io/resource path)]
      {:status 200
       :headers {"Content-Type" type
                 ;; The shell is tiny; never let a stale one outlive a deploy.
                 "Cache-Control" "no-cache"}
       :body (slurp res)})))

(defn make-routes
  "The app host's routes. `identity-opts` is the GM's own (game, seats); the
   app only changes where sign-in returns to. `site-asset` serves the site's
   /assets/* (logo, favicon) unchanged."
  [identity-opts site-asset]
  (let [opts (fn [request]
               (assoc (identity-opts) :app-base-url (gm-identity/request-base-url request)))]
    (routes
     (GET "/health" [] {:status 200 :body {:ok true :app true}})

     (GET "/auth.json" [:as request] (gm-identity/auth-config-response request (opts request)))
     (GET "/auth/callback" [:as request] (gm-identity/ui-redirect-with-query request (opts request)))
     (GET "/auth/success" [:as request] (gm-identity/ui-redirect-with-query request (opts request)))
     (GET "/auth/failure" [:as request] (gm-identity/ui-redirect-with-query request (opts request)))
     (OPTIONS "/auth/login" [] (gm-identity/cors-preflight-response))
     (POST "/auth/login" [:as request] (gm-identity/login-response request))
     (GET "/auth/me" [:as request] (gm-identity/auth-response request))

     (GET "/api/app/games" [:as request] (games-response request))
     (GET "/api/app/games/:game-name/flywheel" [game-name :as request]
       (flywheel-response request game-name (query-param request "range")))

     (GET "/assets/*" [:as request] (site-asset request))
     (GET "/" [] (file-response "/"))
     (GET "/app.js" [] (file-response "/app.js"))
     (GET "/app.css" [] (file-response "/app.css"))

     ;; Everything else is the marketing site's, not the app's.
     (GET "*" [:as request]
       {:status 302
        :headers {"Location" "https://coselling.ai"}
        :body ""})
     (fn [_] (error 404 "not-found" "Not found")))))
