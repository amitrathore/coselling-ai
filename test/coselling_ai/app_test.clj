(ns coselling-ai.app-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [game.gm.config :as gm-config]
            [coselling-ai.app :as app]
            [coselling-ai.handlers :as handlers]))

(defn- req [host uri & {:keys [query-string token method input] :or {method :get}}]
  (let [resp (handlers/app {:request-method method :uri uri :query-string query-string :scheme :https
                            :body (when input (java.io.ByteArrayInputStream. (.getBytes (json/generate-string input) "UTF-8")))
                            :headers (cond-> {"host" host}
                                       token (assoc "authorization" (str "Bearer " token))
                                       input (assoc "content-type" "application/json"))})]
    ;; JSON comes back encoded by wrap-json-response; decode it for the asserts.
    (if (and (string? (:body resp))
             (some-> (get-in resp [:headers "Content-Type"]) (str/includes? "json")))
      (update resp :body #(json/parse-string % true))
      resp)))

(defn- body [resp]
  (let [b (:body resp)]
    (if (instance? java.io.InputStream b) (slurp b) (str b))))

(deftest the-app-host-serves-the-app-and-the-site-is-unchanged
  (with-redefs [gm-config/resolve-app-base-url (constantly "https://coselling.example")]
    (let [shell (req "app.coselling.example" "/")
          html (body shell)]
      (is (= 200 (:status shell)))
      (is (str/includes? html "/app.js"))
      (is (str/includes? html "5Cs Flywheel")))
    (is (= 200 (:status (req "app.coselling.example" "/app.css"))))
    (is (= 200 (:status (req "app.coselling.example" "/assets/images/coselling-favicon.svg")))
        "the app shares the site's assets")
    (is (= 302 (:status (req "app.coselling.example" "/pages/about-us/")))
        "site pages aren't served on the app host")
    (let [site (req "coselling.example" "/")
          html (body site)]
      (is (= 200 (:status site)))
      (is (str/includes? html "script.js"))
      (is (not (str/includes? html "/app.js"))))
    (is (= 301 (:status (req "www.coselling.example" "/"))) "www still goes to the apex")
    (is (= 200 (:status (req "coselling-ai.fly.dev" "/health"))) "MoM's way in is untouched")))

(deftest sign-in-returns-to-the-app-host
  ;; Browser storage is per origin: a token landing on coselling.ai is lost to the app.
  (with-redefs [gm-config/resolve-app-base-url (constantly "https://coselling.example")]
    (let [resp (req "app.coselling.example" "/auth/success" :query-string "token=t1")]
      (is (= "https://app.coselling.example/?token=t1" (get-in resp [:headers "Location"]))))
    (is (= "https://coselling.example/?token=t1" (get-in (req "coselling.example" "/auth/success" :query-string "token=t1")
                                [:headers "Location"]))
        "the site's own redirect is unchanged")))

(deftest dashboard-games-are-read-as-the-caller
  (let [calls (atom [])]
    (with-redefs [http/get (fn [url opts]
                             (swap! calls conj [url (get-in opts [:headers "Authorization"])])
                             {:status 200 :body {:owner-id "u1"
                                                 :games [{:id "2" :game-name "zeta" :name "Zeta" :protocols ["market"]}
                                                         {:id "1" :game-name "alpha" :name "Alpha" :host-secret "x"}]}})]
      (let [resp (req "app.coselling.example" "/api/app/games" :token "tok-op")
            games (get-in resp [:body :games])]
        (is (= 200 (:status resp)))
        (is (= ["alpha" "zeta"] (map :game-name games)) "sorted by name")
        (is (not-any? :host-secret games) "only the fields the picker needs")
        (is (= 1 (count @calls)) "the stub was actually called")
        (is (str/ends-with? (ffirst @calls) "/api/v1/dashboard/games"))
        (is (= "Bearer tok-op" (second (first @calls))) "the operator's own token, not the GM's")))))

(deftest signed-out-callers-never-reach-mom
  (let [called (atom false)]
    (with-redefs [http/get (fn [& _] (reset! called true) {:status 200 :body {}})]
      (is (= 401 (:status (req "app.coselling.example" "/api/app/games"))))
      (is (= 401 (:status (req "app.coselling.example" "/api/app/games/the-js/flywheel"))))
      (is (false? @called)))))

(deftest dashboard-preserves-mom-json-errors
  (doseq [[path status code] [["/api/app/games" 401 "unauthorized"]
                             ["/api/app/games/the-js/flywheel" 403 "access-required"]]]
    (with-redefs [http/get (fn [_ opts]
                            (http/coerce-response-body
                             opts {:status status
                                   :headers {"content-type" "application/json"}
                                   :body (.getBytes (json/generate-string {:error code}) "UTF-8")}))]
      (let [response (req "app.coselling.example" path :token "invalid-or-revoked")]
        (is (= status (:status response)))
        (is (= code (get-in response [:body :error])))))))

(deftest the-flywheel-rechecks-access-on-every-request
  (let [calls (atom [])
        allowed? (atom true)]
    (with-redefs [http/get (fn [url opts]
                            (swap! calls conj [url (:query-params opts)])
                            (if @allowed?
                              {:status 200 :body {:range "7d" :game {:name "Delegated"} :cs []}}
                              {:status 403 :body {:error "access-required"}}))]
      (let [uri "/api/app/games/delegated/flywheel"
            request #(req "app.coselling.example" uri :query-string "range=7d" :token "operator")]
        (is (= "7d" (get-in (request) [:body :range])))
        (reset! allowed? false)
        (is (= 403 (:status (request))))
        (is (= 2 (count @calls)))
        (is (str/ends-with? (ffirst @calls) "/api/v1/dashboard/games/delegated/flywheel"))
        (is (= {:range "7d"} (second (first @calls))))))))

(deftest mutations-forward-only-the-signed-in-users-credential
  (let [calls (atom [])
        capture (fn [url opts]
                  (swap! calls conj [url (get-in opts [:headers "Authorization"])
                                     (json/parse-string (:body opts) true)])
                  {:status 200 :body {:success true}})
        base "/api/app/games/sample/"]
    (with-redefs [http/put capture http/post capture]
      (is (= 401 (:status (req "app.coselling.example" (str base "access")
                               :method :put :input {:enabled true}))))
      (is (= 200 (:status (req "app.coselling.example" (str base "access")
                               :method :put :input {:enabled true} :token "host"))))
      (is (= 200 (:status (req "app.coselling.example" (str base "actions")
                               :method :post :input {:seat "seat" :data {}} :token "operator"))))
      (is (= [["Bearer host" {:enabled true}] ["Bearer operator" {:seat "seat" :data {}}]]
             (mapv #(subvec % 1) @calls))))))

(deftest game-names-are-checked-before-they-reach-a-url
  (is (= 400 (:status (app/flywheel-response
                       {:headers {"authorization" "Bearer t"}} "../admin" "30d")))))

(deftest app-host-detection
  (is (app/app-host? "app.coselling.ai"))
  (is (app/app-host? "APP.coselling.ai:443"))
  (is (app/app-host? "app.localhost:8894"))
  (is (not (app/app-host? "coselling.ai")))
  (is (not (app/app-host? "coselling-ai.fly.dev")))
  (is (not (app/app-host? nil))))

(defn- root-tokens [css]
  (let [block (second (re-find #"(?s):root\s*\{(.*?)\}" css))]
    (into {} (map (fn [[_ k v]] [k (str/trim v)]))
          (re-seq #"(--[a-z-]+):\s*([^;]+);" block))))

(deftest the-app-wears-the-sites-brand
  (let [site (root-tokens (slurp "resources/site/styles.css"))
        app (root-tokens (slurp "resources/app/app.css"))]
    (is (< 10 (count site)))
    (doseq [[k v] site]
      (is (= v (get app k)) (str k " must match the site")))))
