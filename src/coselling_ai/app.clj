(ns coselling-ai.app
  "Dashboard routes that forward the signed-in human’s Intergraph credential to MoM."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [compojure.core :refer [routes GET POST PUT OPTIONS]]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [ring.util.codec :as codec]
            [game.gm.identity :as gm-identity]))

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

(defn- error [status code message]
  {:status status :headers {"Cache-Control" "no-store"}
   :body {:error code :message message}})

(defn- unavailable []
  (error 502 "mom-unavailable" "Couldn't reach Intergraph. Try again in a moment."))

(defn- mom-response [request path query]
  (if-not (gm-identity/auth-header (:headers request))
    (error 401 "sign-in-required" "Sign in to open the dashboard.")
    (try
      (let [method (:request-method request :get)
            send (case method :get http/get :post http/post :put http/put)
            options (cond-> {:as :json :throw-exceptions false
                             :socket-timeout 15000 :conn-timeout 5000}
                      query (assoc :query-params query)
                      (not= :get method) (assoc :content-type :json
                                                 :body (json/generate-string (:body request))))
            {:keys [status body]} (send (str (gm-identity/mom-url nil) path)
                                        (gm-identity/request-user-auth request options))]
        (if (map? body)
          {:status status :headers {"Cache-Control" "no-store"} :body body}
          (unavailable)))
      (catch java.io.IOException _ (unavailable))
      (catch clojure.lang.ExceptionInfo ex
        (if (:status (ex-data ex))
          (unavailable)
          (throw ex))))))

(defn games-response [request]
  (let [response (mom-response request "/api/v1/dashboard/games" nil)]
    (if (= 200 (:status response))
      (update-in response [:body :games]
                 (fn [games]
                   (->> games
                        (map #(select-keys % [:id :game-name :name :description :protocols :host]))
                        (sort-by (comp str/lower-case str :name))
                        vec)))
      response)))

(def ^:private game-name-pattern #"[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")

(defn- game-response [request game-name resource query]
  (if (re-matches game-name-pattern (str game-name))
    (mom-response request (str "/api/v1/dashboard/games/" game-name "/" resource) query)
    (error 400 "bad-game" "That isn't a game name.")))

(defn flywheel-response [request game-name range-key]
  (game-response request game-name "flywheel" (when range-key {:range range-key})))

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

     (GET "/api/app/games/:game-name/access" [game-name :as request]
       (game-response request game-name "access" nil))
     (PUT "/api/app/games/:game-name/access" [game-name :as request]
       (game-response request game-name "access" nil))
     (GET "/api/app/games/:game-name/actions" [game-name :as request]
       (game-response request game-name "actions" (when-let [seat (query-param request "seat")]
                                                  {:seat seat})))
     (POST "/api/app/games/:game-name/actions" [game-name :as request]
       (game-response request game-name "actions" nil))

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
