(ns coselling-ai.marketplace
  "Payment HTTP boundary, ported from agents-of-mind. Protocol state stays in MoM."
  (:require [clojure.string :as str] [cheshire.core :as json]
            [clj-http.client :as http] [compojure.core :refer [defroutes GET POST OPTIONS]]
            [game.gm.config :as gm-config] [game.gm.identity :as gm-identity]
            [game.gm.runtime :as gm-runtime] [coselling-ai.payments :as payments]
            [coselling-ai.seller-commerce :as seller-commerce]
            [coselling-ai.stripe :as stripe]))

(defn- current-game-name [] (some-> @gm-runtime/game-name-handle str))
(defn- log! [level message & [data]] (println level message (pr-str data)))
(def mom-url gm-identity/mom-url)
(def with-auth gm-identity/with-auth)
(defn- require-current-game! [game-name]
  (when-not (= (str game-name) (str (current-game-name)))
    (throw (ex-info "Payment event belongs to another game" {:type :wrong-game}))))
(defn submit-service-event [game-name & args]
  (require-current-game! game-name)
  (apply gm-identity/submit-service-event game-name args))
(defn connect-webhook-secret []
  (some-> (System/getenv "STRIPE_CONNECT_WEBHOOK_SECRET") str/trim not-empty))
(defn- stub-provider-requested? []
  (= "stub" (some-> (System/getenv "MARKET_PAYMENT_PROVIDER") str/trim str/lower-case)))
(defn payments-ready?
  "Stripe needs its publishable key and the platform webhook secret. The Connect
   webhook secret is not required, unlike agents-of-mind: every listing here uses
   the market's own checkout, and connected-account events only matter to
   seller-direct checkout. The stub provider counts only when asked for by name,
   so a deployment that merely lacks Stripe keys stays unavailable."
  []
  (case (payments/active-provider-id)
    "stripe" (boolean (and (seq (gm-config/resolve-stripe-publishable-key))
                           (seq (gm-config/resolve-stripe-webhook-secret))))
    "stub" (stub-provider-requested?)
    false))
(defn- require-selected-seat [request]
  (gm-identity/require-selected-seat request
    {:game-id (some-> @gm-runtime/game-id str) :game-name (current-game-name)
     :select-sole-seat? true}))

(defn- query-checkouts-by-buyer
  "Returns the vector of checkout maps owned by `player-name` in `game-name`."
  [game-name player-name]
  (let [resp (submit-service-event game-name "market/query-checkouts"
                                   player-name ["buyer"]
                                   {:filters {:buyer player-name}})
        body (:body resp)
        gm-result (or (:gm-result body) (get body "gm-result"))
        data (or (:data gm-result) (get gm-result "data"))]
    (or (:checkouts data) (get data "checkouts") [])))


(defn- query-checkout-batches-by-buyer
  [game-name player-name]
  (let [resp (submit-service-event game-name "market/query-checkout-batches"
                                   player-name ["buyer"]
                                   {:filters {:buyer player-name}})
        body (:body resp)
        gm-result (or (:gm-result body) (get body "gm-result"))
        data (or (:data gm-result) (get gm-result "data"))]
    (or (:checkout-batches data) (get data "checkout-batches") [])))


(defn- query-orders-by-buyer
  [game-name player-name]
  (let [resp (submit-service-event game-name "market/query-orders"
                                   player-name ["buyer"]
                                   {:filters {:buyer player-name}})
        body (:body resp)
        gm-result (or (:gm-result body) (get body "gm-result"))
        data (or (:data gm-result) (get gm-result "data"))]
    (or (:orders data) (get data "orders") [])))

(defn- query-subscriptions-by-buyer-result [game-name player-name]
  (let [resp (submit-service-event game-name "market/query-subscriptions"
                                   player-name ["buyer"]
                                   {:filters {:buyer player-name}})
        result (or (get-in resp [:body :gm-result])
                   (get-in resp [:body "gm-result"]))
        subs (or (get-in result [:data :subscriptions])
                 (get-in result ["data" "subscriptions"]))]
    {:ok? (and (<= 200 (long (or (:status resp) 0)) 299)
               (or (:success result) (get result "success"))
               (sequential? subs))
     :subscriptions (vec (or subs []))}))

(defn- query-subscriptions-by-buyer [game-name player-name]
  (:subscriptions (query-subscriptions-by-buyer-result game-name player-name)))

(defn- active-subscription-for-order
  "Guard seller subscription families before creating a second Stripe Checkout."
  [subscriptions order caps]
  (let [cap-id (or (:capability-id order) (get order "capability-id"))
        cap (some #(when (= (str cap-id) (str (or (:id %) (get % "id")))) %) caps)
        group (or (get-in cap [:commerce :subscription-group])
                  (get-in cap ["commerce" "subscription-group"]))
        seller (or (:seller order) (get order "seller"))]
    (when (and group seller)
      (some #(when (and (= (str seller) (str (or (:seller %) (get % "seller"))))
                       (= (str group) (str (or (:subscription-group %)
                                                (get % "subscription-group"))))
                       (contains? #{"active" "pending-activation" "past-due"
                                    "cancel-at-period-end" "paused"}
                                  (-> (or (:status %) (get % "status"))
                                      str (str/replace #"^:" ""))))
               %)
            subscriptions))))


(defn- query-checkout-payment-details
  "Fetch the GM-only payment projection for one checkout. Unlike
   market/query-orders, this carries the exact aggregate application fee while
   keeping each order's commission breakdown private."
  [game-name checkout-id]
  (let [resp (submit-service-event game-name "market/query-checkout-payment-details"
                                   "game-master" ["game-master"]
                                   {:checkout-id (str checkout-id)})
        body (:body resp)
        gm-result (or (:gm-result body) (get body "gm-result"))
        data (or (:data gm-result) (get gm-result "data"))
        success (or (:success gm-result) (get gm-result "success"))
        fee (or (:application-fee data) (get data "application-fee"))]
    (if (and (<= 200 (long (or (:status resp) 0)) 299)
             (not= false success)
             (map? data)
             (map? fee))
      {:ok? true
       :orders (vec (or (:orders data) (get data "orders") []))
       :application-fee-amount
       (long (or (:amount fee) (get fee "amount") 0))}
      {:ok? false
       :status (:status resp)
       :error (or (:error gm-result) (get gm-result "error")
                  (:error body) (get body "error"))})))


(defn- query-capabilities
  [game-name actor]
  (let [resp (submit-service-event game-name "market/query-capabilities"
                                   actor ["buyer"] {})
        body (:body resp)
        gm-result (or (:gm-result body) (get body "gm-result"))
        data (or (:data gm-result) (get gm-result "data"))]
    (or (:capabilities data) (get data "capabilities") [])))

(defn- refund-field [m k]
  (or (get m k) (get m (name k))))

(defn- refund-result [response]
  (or (get-in response [:body :gm-result])
      (get-in response [:body "gm-result"])))

(defn- refund-event-succeeded? [response]
  (let [result (refund-result response)]
    (and (<= 200 (long (or (:status response) 0)) 299)
         (true? (refund-field result :success))
         (every? #(= "succeeded"
                     (some-> (refund-field % :status) str (str/replace #"^:" "")))
                 (or (get-in response [:body :effect-results])
                     (get-in response [:body "effect-results"]) [])))))

(defn- refund-query-item [game-name actor roles event-type filter-key item-key item-id]
  (let [response (submit-service-event game-name event-type actor roles
                                       {:filters {filter-key item-id}})
        result (refund-result response)
        data (refund-field result :data)
        items (refund-field data item-key)]
    (when (refund-event-succeeded? response)
      (some #(when (= (str item-id) (str (refund-field % :id))) %) items))))

(defn- seller-direct-refund [context seat request]
  (let [game-name (:game-name context)
        actor (:player-name seat)
        roles (vec (or (:player-roles seat)
                       (some-> (:player-role seat) vector) []))
        body (:body request)
        order-id (or (refund-field body :order-id) (refund-field body :orderId))
        return-id (or (refund-field body :return-id) (refund-field body :returnId))
        amount (refund-field body :amount)
        reason (refund-field body :reason)]
    (cond
      (str/blank? (str order-id))
      {:status 400 :body {:error {:code "order-id-required"}}}

      (not (and (integer? amount) (pos? amount)))
      {:status 400 :body {:error {:code "invalid-amount"}}}

      :else
      (let [order (refund-query-item game-name actor roles "market/query-orders"
                                     :order-id :orders order-id)
            checkout-id (refund-field order :checkout-id)
            checkout (when checkout-id
                       (refund-query-item game-name actor roles "market/query-checkouts"
                                          :checkout-id :checkouts checkout-id))
            flow (some-> (refund-field checkout :funds-flow)
                         str (str/replace #"^:" ""))
            payment-ref (refund-field checkout :payment-ref)
            connected-account (refund-field payment-ref :connected-account)
            direct-payment-intent-id (or (refund-field payment-ref :payment-intent-id)
                                         (refund-field payment-ref :transaction-id))
            payment-lookup (when (and (= "seller-direct" flow)
                                      (str/blank? (str direct-payment-intent-id))
                                      (not (str/blank? (str connected-account))))
                             (stripe/resolve-checkout-payment-intent
                              {:secret-key (gm-config/resolve-stripe-secret-key)
                               :session-id (refund-field payment-ref :session-id)
                               :connected-account-id connected-account
                               :checkout-id checkout-id :game-name game-name
                               :amount (refund-field (refund-field checkout :total)
                                                     :amount)}))
            payment-intent-id (or direct-payment-intent-id
                                  (:payment-intent-id payment-lookup))]
        (cond
          (nil? order)
          {:status 404 :body {:error {:code "order-not-found"}}}

          (not= actor (refund-field order :seller))
          {:status 403 :body {:error {:code "not-order-seller"}}}

          (nil? checkout)
          {:status 503 :body {:error {:code "checkout-unavailable"}}}

          (not= "seller-direct" flow)
          {:status 409 :body {:error {:code "not-seller-direct-order"}}}

          (or (str/blank? (str payment-intent-id))
              (str/blank? (str connected-account)))
          {:status 409 :body {:error {:code "payment-route-unknown"}}}

          :else
          (let [refund-id (str "refund-" (random-uuid))
                issued (submit-service-event
                        game-name "market/issue-refund" actor roles
                        (cond-> {:order-id (str order-id) :refund-id refund-id
                                 :amount amount}
                          return-id (assoc :return-id return-id)
                          reason (assoc :reason reason)))
                issue-data (refund-field (refund-result issued) :data)
                issue-status (some-> (refund-field issue-data :status)
                                     str (str/replace #"^:" ""))]
            (cond
              (not (refund-event-succeeded? issued))
              {:status (or (:status issued) 409)
               :body (or (:body issued) {:error {:code "refund-rejected"}})}

              (not= "reserved" issue-status)
              {:status 409 :body {:error {:code "refund-not-reserved"}
                                  :refundId refund-id}}

              :else
              (let [refund (payments/create-refund
                            {:payment-intent-id payment-intent-id
                             :connected-account-id connected-account
                             :amount amount :reason reason
                             :refund-id refund-id :order-id (str order-id)
                             :checkout-id checkout-id :game-name game-name})]
                (cond
                  (and (:ok? refund) (= "succeeded" (str (:status refund))))
                  (let [settled (submit-service-event
                                 game-name "market/mark-refund-settled" actor roles
                                 {:order-id (str order-id) :refund-id refund-id
                                  :stripe-refund-id (:id refund)})]
                    (if (refund-event-succeeded? settled)
                      {:status 200 :body {:refundId refund-id :orderId order-id
                                          :stripeRefundId (:id refund) :amount amount
                                          :status "refunded"}}
                      {:status 503 :body {:error {:code "refund-settlement-pending"}
                                          :refundId refund-id :stripeRefundId (:id refund)}}))

                  (:ok? refund)
                  {:status 202 :body {:refundId refund-id :orderId order-id
                                      :stripeRefundId (:id refund)
                                      :status "payment-pending"}}

                  (>= (long (or (:status refund) 0)) 500)
                  {:status 503 :body {:error {:code "refund-processor-uncertain"}
                                      :refundId refund-id :status "reserved"}}

                  :else
                  (do
                    (submit-service-event game-name "market/reverse-refund" actor roles
                                          {:order-id (str order-id) :refund-id refund-id
                                           :reason (or (:error-code refund) "refund-failed")})
                    {:status 402 :body {:error {:code "refund-failed"
                                                :reason (:error-code refund)}
                                        :refundId refund-id :status "rejected"}}))))))))))


(defn- fallback-payment-game-name
  []
  (or (current-game-name) (gm-config/resolve-game-name nil)))

;; --- Stripe Connect: payout accounts + withdrawals ---


(defn- fetch-game-merchant-of-record
  "GET the game's merchant-of-record decision from MoM (service auth): whether a
   marketplace charge may name the game via on_behalf_of. Cheap (no Stripe on
   MoM's side).

   Returns a CATEGORIZED result, never nil. Collapsing a non-200, a timeout and an
   exception to nil is what let checkout fail open — the caller could not tell
   'the game is not connected' from 'we could not ask', and charged without
   on_behalf_of either way.

     {:ok? true  :body {:decision \"on-behalf-of\" :accountId \"acct_...\"}}
     {:ok? true  :body {:decision \"blocked\" :reason \"game-not-connected\"}}
     {:ok? false :kind :dependency :status <int|nil>}  ; unreachable, timeout, non-200
     {:ok? false :kind :malformed  :details <string>}  ; 200 we cannot interpret"
  [game-name]
  (try
    (let [resp (http/get (str (mom-url nil) "/api/v1/connect/" game-name "/merchant-of-record")
                         (with-auth {:as :json :coerce :unexceptional :throw-exceptions false}))
          body (:body resp)]
      (cond
        (not= 200 (:status resp))
        {:ok? false :kind :dependency :status (:status resp)}

        (not (map? body))
        {:ok? false :kind :malformed :details "response body is not a map"}

        (str/blank? (str (or (:decision body) (get body "decision"))))
        {:ok? false :kind :malformed :details "response has no decision"}

        :else {:ok? true :body body}))
    (catch Exception e
      {:ok? false :kind :dependency :status nil :details (.getMessage e)})))


(def ^:private stripe-account-id-re #"^acct_[A-Za-z0-9]+$")


(defn- merchant-attribution
  "Decides whether a native Stripe checkout may proceed, from the categorized
   merchant-of-record result. Returns {:ok? true :on-behalf-of \"acct_...\"} or
   {:ok? false :response <ring response>}.

   Fail CLOSED: only an on-behalf-of decision carrying a well-formed account id
   proceeds. Everything else rejects before Stripe is called, because a charge
   created without on_behalf_of makes Intergraph the merchant of record."
  [game-name mor]
  (let [reject (fn [status code message & [extra]]
                 (log! "WARN" "merchant attribution rejected checkout"
                       (merge {:game-name game-name :error-code code :status status} extra))
                 {:ok? false
                  :response {:status status
                             :body {:error (merge {:code code :message message
                                                   :game-name game-name}
                                                  extra)}}})
        body (:body mor)
        decision (when (:ok? mor) (some-> (or (:decision body) (get body "decision")) str))
        account-id (when (:ok? mor) (str (or (:accountId body) (get body "accountId") "")))]
    (cond
      ;; We could not ask MoM. Readiness is unknown, so we must not charge.
      ;; nil is included defensively: no result at all is no information, which is
      ;; a dependency failure, not permission to proceed.
      (or (nil? mor)
          (and (not (:ok? mor)) (= :dependency (:kind mor))))
      (reject 503 "merchant-readiness-unavailable"
              "Could not confirm the game's merchant account with MoM; checkout is unavailable."
              {:dependency-status (:status mor)})

      (not (:ok? mor))
      (reject 502 "merchant-decision-invalid"
              "MoM returned a merchant-of-record response that could not be interpreted."
              {:details (:details mor)})

      (= "blocked" decision)
      (reject 409 "game-not-connected"
              "This game has not completed merchant onboarding, so its charges are blocked."
              {:reason (or (:reason body) (get body "reason"))})

      ;; platform-only was removed with the hard merchant gate. An older MoM still
      ;; sending it is a protocol mismatch, not permission to charge.
      (not= "on-behalf-of" decision)
      (reject 502 "merchant-decision-invalid"
              "MoM returned an unrecognized merchant-of-record decision."
              {:decision decision})

      (str/blank? account-id)
      (reject 502 "merchant-decision-invalid"
              "MoM named the game as merchant of record but supplied no account id."
              {:decision decision})

      (not (re-matches stripe-account-id-re account-id))
      (reject 409 "merchant-account-invalid"
              "The game's stored merchant account id is not a valid Stripe account."
              {:decision decision})

      :else {:ok? true :on-behalf-of account-id})))


(defn- upsert-connect-account!
  "PUT the (owner, game) payout account to MoM (service auth)."
  [game-name owner-id fields]
  (require-current-game! game-name)
  (http/put (str (mom-url nil) "/api/v1/connect/" game-name "/account")
            (with-auth {:content-type :json
                        :body (json/generate-string (assoc fields :owner-id (str owner-id)))
                        :as :json :coerce :unexceptional :throw-exceptions false})))


(defn- update-connect-account-by-external-id!
  [game-name fields]
  (require-current-game! game-name)
  (http/put (str (mom-url nil) "/api/v1/connect/" game-name "/account/by-external-id")
            (with-auth {:content-type :json
                        :body (json/generate-string fields)
                        :as :json :coerce :unexceptional :throw-exceptions false})))


(defn- resolve-seller-connect-accounts
  [game-name seller-names]
  (try
    (let [resp (http/post (str (mom-url nil) "/api/v1/connect/" game-name "/sellers/resolve")
                          (with-auth {:content-type :json
                                      :body (json/generate-string {:seller-names (vec seller-names)})
                                      :as :json :coerce :unexceptional :throw-exceptions false}))]
      (if (= 200 (:status resp))
        {:ok? true
         :accounts (or (get-in resp [:body :accounts])
                       (get-in resp [:body "accounts"]) {})}
        {:ok? false :status (:status resp)}))
    (catch Exception e
      {:ok? false :error (.getMessage e)})))


(defn- addon-line-item
  "Builds a single-quantity line item for an order-level pass-through addon
   (shipping or tax), or nil when the addon is absent/zero. Both are quoted
   live off the seller's own store (e.g. WooCommerce's Store API) and must be
   charged verbatim so the Stripe total matches what the ledger settlement
   expects (see coseller.clj order-gross)."
  [order addon-key title currency]
  (let [addon (or (get order addon-key) (get order (name addon-key)))
        amount (or (:amount addon) (get addon "amount") 0)]
    (when (pos? amount)
      {:title title
       :amount amount
       :currency (or (:currency addon) (get addon "currency") currency)
       :quantity 1})))


(defn- build-payment-line-items
  "Builds checkout provider line items from a checkout's orders. Uses capability titles
   when available, otherwise falls back to the order id.

   Providers generally want a per-unit amount × quantity. Listing fast-path orders snapshot
   :unit-price and :quantity under :accepted-terms (where :price is the line
   total); negotiated orders carry only :price. We prefer the snapshotted
   unit-price; otherwise we back the unit amount out of the total / quantity so
   the provider total always matches the order total.

   Each order may also carry top-level :shipping/:tax pass-through amounts
   (see market.clj snapshot-order); when present and non-zero, an extra
   single-quantity line item is appended per leg so the actual Stripe charge
   includes them."
  [orders capability-index]
  (mapcat
    (fn [order]
      (let [terms (or (:accepted-terms order) (get order "accepted-terms"))
            price (or (:price terms) (get terms "price"))
            unit-price (or (:unit-price terms) (get terms "unit-price"))
            raw-qty (or (:quantity terms) (get terms "quantity"))
            qty (if (and (integer? raw-qty) (pos? raw-qty)) (long raw-qty) 1)
            total-amount (or (:amount price) (get price "amount") 0)
            unit-amount (cond
                          unit-price (or (:amount unit-price) (get unit-price "amount"))
                          (> qty 1)  (quot (long total-amount) qty)
                          :else      total-amount)
            currency (or (:currency unit-price) (get unit-price "currency")
                         (:currency price) (get price "currency"))
            cap-id (or (:capability-id order) (get order "capability-id"))
            cap (get capability-index cap-id)
            title (or (:title cap) (get cap "title") cap-id "Item")
            ;; Carry the capability's recurring terms through to the provider.
            ;; Without this the line looks one-time, the session is created in
            ;; "payment" mode, and a subscription silently becomes a single
            ;; charge — the protocol and the Stripe layer each behaving
            ;; correctly while nothing connects them.
            ;; Read from unit-price FIRST, exactly as :amount and :currency
            ;; above do. The listing fast-path splits terms: :price carries the
            ;; line total with :recurring stripped, while :unit-price keeps it.
            ;; Reading only :price silently produced a one-time line for a
            ;; recurring capability — the state was right and the charge was
            ;; wrong.
            recurring (or (:recurring unit-price) (get unit-price "recurring")
                          (:recurring price) (get price "recurring"))
            price-ref (or (get-in cap [:commerce :price-ref])
                          (get-in cap ["commerce" "price-ref"]))
            item (cond-> {:title title
                          :amount unit-amount
                          :currency currency
                          :quantity qty}
                   recurring (assoc :recurring recurring)
                   price-ref (assoc :price-ref price-ref))]
        (concat [item]
                ;; Shipping/tax are one-time lines. Stripe refuses a session
                ;; mixing recurring and one-time prices, and a subscription has
                ;; nothing to ship, so they are omitted for recurring orders
                ;; rather than silently breaking checkout.
                (when-not recurring
                  (remove nil? [(addon-line-item order :shipping "Shipping" currency)
                                (addon-line-item order :tax "Tax" currency)])))))
    orders))

(defn- checkout-lines-match? [checkout orders lines]
  (let [total (:total checkout)
        currency (some-> (:currency total) str str/upper-case)]
    (and (seq lines)
         (= (set (map str (:orders checkout))) (set (map #(str (:id %)) orders)))
         (every? #(and (integer? (:amount %)) (not (neg? (:amount %)))
                       (integer? (:quantity %)) (pos? (:quantity %))
                       (= currency (some-> (:currency %) str str/upper-case))) lines)
         (= (:amount total) (reduce + (map #(* (:amount %) (:quantity %)) lines))))))

(defn- success-url-template
  []
  (str (or (gm-config/resolve-app-base-url) "")
       "/pages/launch-your-network/?market_checkout=success&session_id={CHECKOUT_SESSION_ID}"))


(defn- cancel-url-template
  []
  (str (or (gm-config/resolve-app-base-url) "")
       "/pages/launch-your-network/?market_checkout=cancel"))


(defn- seller-checkout-return-url
  [checkout-id]
  (str (or (gm-config/resolve-app-base-url) "")
       "/pages/launch-your-network/?market_checkout=return&checkout_id="
       (java.net.URLEncoder/encode (str checkout-id) "UTF-8")
       "&session_id={CHECKOUT_SESSION_ID}"))


(defn- order-marketplace-grant
  [order]
  (let [terms (or (:accepted-terms order) (get order "accepted-terms"))
        price (or (:price terms) (get terms "price"))]
    {:order-id (str (or (:id order) (get order "id")))
     :sku (str (or (:external-sku order) (get order "external-sku")))
     :quantity (long (or (:quantity terms) (get terms "quantity") 1))
     :money {:amount (long (or (:amount price) (get price "amount") 0))
             :currency (str/upper-case
                        (str (or (:currency price) (get price "currency") "USD")))}}))


(defn- prepare-seller-checkout
  [{:keys [adapter checkout batch buyer recipient-email orders commission-bps]}]
  (let [checkout-id (str (or (:id checkout) (get checkout "id")))
        subscription-id (or (:market-subscription-id checkout)
                            (get checkout "market-subscription-id")
                            (str "subscription_" checkout-id))
        prepared (seller-commerce/prepare-checkout!
                  adapter
                  {:checkout_id checkout-id
    :checkout_batch_id (str (or (:id batch) (get batch "id")))
    :market_subscription_id subscription-id
    :buyer (str buyer)
    :recipient {:type "email" :value recipient-email}
    :catalog_version (long (or (:catalog-version (first orders))
                               (get (first orders) "catalog-version") 0))
    :application_fee_bps (long commission-bps)
    :orders (mapv order-marketplace-grant orders)})]
    (cond-> prepared
      (:ok? prepared) (assoc :market-subscription-id subscription-id))))

(defn- verify-seller-commerce-payment [payment]
  (seller-commerce/confirm-payment! (:commerce-adapter payment) payment))


(defroutes routes
  (GET "/api/market/payment-mode" []
    {:status 200
     :body (if (payments-ready?) (payments/payment-mode) {:mode "unavailable"})})
  (POST "/api/market/refund" [:as request]
    (let [{:keys [context seat response]} (require-selected-seat request)]
      (or response (seller-direct-refund context seat request))))
  (OPTIONS "/api/market/refund" []
    {:status 204
     :headers {"Access-Control-Allow-Origin" "*"
               "Access-Control-Allow-Headers" "Content-Type, Authorization, X-Selected-Seat"
               "Access-Control-Allow-Methods" "POST, OPTIONS"}})
  (GET "/api/market/subscriptions" [:as request]
    (let [{:keys [context seat response]} (require-selected-seat request)]
      (if response response
          (let [result (query-subscriptions-by-buyer-result
                        (:game-name context) (:player-name seat))]
            (if (:ok? result)
              {:status 200 :body {:subscriptions (:subscriptions result)}}
              {:status 503 :body {:error {:code "subscriptions-unavailable"}}})))))
  (POST "/api/market/subscriptions/:subscription-id/portal" [subscription-id :as request]
    (let [{:keys [context seat response]} (require-selected-seat request)]
      (if response
        response
        (let [game-name (:game-name context)
              buyer (:player-name seat)
              subscription (some #(when (= (str subscription-id)
                                                (str (or (:id %) (get % "id")))) %)
                                 (query-subscriptions-by-buyer game-name buyer))
              provider-id (or (:provider-subscription-id subscription)
                              (get subscription "provider-subscription-id"))
              adapter-id (or (:commerce-adapter subscription)
                             (get subscription "commerce-adapter"))
              seller (or (:seller subscription) (get subscription "seller"))
              account-result (when seller (resolve-seller-connect-accounts game-name [seller]))
              account (or (get (:accounts account-result) seller)
                          (get (:accounts account-result) (keyword (str seller))))
              adapter-cfg (seller-commerce/adapter adapter-id)
              account-id (seller-commerce/stripe-account-id adapter-cfg seller account)
              target-id (or (get-in request [:body :target-capability-id])
                            (get-in request [:body :targetCapabilityId]))
              target (when target-id
                       (some #(when (= (str target-id)
                                      (str (or (:id %) (get % "id")))) %)
                             (query-capabilities game-name buyer)))
              current-cap (or (:capability-snapshot subscription)
                              (get subscription "capability-snapshot"))
              current-amount (or (get-in current-cap [:terms :price :amount])
                                 (get-in current-cap ["terms" "price" "amount"]))
              current-group (or (:subscription-group subscription)
                                (get subscription "subscription-group"))
              target-amount (or (get-in target [:terms :price :amount])
                                (get-in target ["terms" "price" "amount"]))
              target-price (or (get-in target [:commerce :price-ref])
                               (get-in target ["commerce" "price-ref"]))]
          (cond
            (nil? subscription) {:status 404 :body {:error {:code "subscription-not-found"}}}
            (or (str/blank? (str provider-id))
                (str/starts-with? (str provider-id) "free:"))
            {:status 409 :body {:error {:code "subscription-is-free"
                                        :message "The Free plan has no Stripe billing portal."}}}
            (and target-id
                 (or (nil? target)
                     (str/blank? (str (or current-group "")))
                     (not= "active" (-> (or (:status target) (get target "status"))
                                         str (str/replace #"^:" "")))
                     (not= (str seller) (str (or (:seller target) (get target "seller"))))
                     (not= (str current-group)
                           (str (or (get-in target [:commerce :subscription-group])
                                    (get-in target ["commerce" "subscription-group"]))))
                     (not (and (number? current-amount) (number? target-amount)
                               (> target-amount current-amount)))
                     (str/blank? (str target-price))))
            {:status 409 :body {:error {:code "invalid-subscription-upgrade"
                                        :message "Choose a higher-priced plan in this subscription family."}}}
            (str/blank? (str account-id))
            {:status 409 :body {:error {:code "seller-account-not-ready"}}}
            :else
            (let [retrieved (stripe/retrieve-subscription
                             {:secret-key (gm-config/resolve-stripe-secret-key)
                              :connected-account-id account-id
                              :subscription-id provider-id})
                  customer (or (get-in retrieved [:subscription :customer])
                               (get-in retrieved [:subscription "customer"]))
                  items (or (get-in retrieved [:subscription :items :data])
                            (get-in retrieved [:subscription "items" "data"]))
                  item-id (or (:id (first items)) (get (first items) "id"))
                  portal (when (and (:ok? retrieved)
                                    (or (nil? target-id)
                                        (and (= 1 (count items)) item-id)))
                           (stripe/create-customer-portal-session
                            {:secret-key (gm-config/resolve-stripe-secret-key)
                             :connected-account-id account-id
                             :customer-id customer
                             :subscription-id (when target-id provider-id)
                             :subscription-item-id (when target-id item-id)
                             :target-price-id (when target-id target-price)
                             :return-url (str (gm-config/resolve-app-base-url)
                                              "/pages/launch-your-network/")}))]
              (if (:ok? portal)
                {:status 200 :body {:url (:url portal)}}
                {:status 502 :body {:error {:code "stripe-portal-unavailable"
                                            :details (or (:error portal)
                                                         (:error retrieved))}}})))))))
  (POST "/api/market/checkout-batches/:batch-id/checkouts/:checkout-id/session"
        [batch-id checkout-id :as request]
    (let [{:keys [context seat response]} (require-selected-seat request)]
      (if response
        response
        (let [game-name (:game-name context)
              buyer (:player-name seat)
              recipient-email (or (get-in request [:body :recipient-email])
                                  (get-in request [:body :recipientEmail]))
              attempt-id (or (get-in request [:body :attempt-id])
                             (get-in request [:body :attemptId]))
              batches (query-checkout-batches-by-buyer game-name buyer)
              batch (some #(when (= (str batch-id)
                                       (str (or (:id %) (get % "id")))) %)
                          batches)
              group-ids (set (map str (or (:checkouts batch)
                                          (get batch "checkouts") [])))
              checkout (some #(when (= (str checkout-id)
                                          (str (or (:id %) (get % "id")))) %)
                             (query-checkouts-by-buyer game-name buyer))
              status (some-> (or (:status checkout) (get checkout "status"))
                             str (str/replace #"^:" ""))
              funds-flow (some-> (or (:funds-flow checkout) (get checkout "funds-flow"))
                                 str (str/replace #"^:" ""))
              adapter (some-> (or (:commerce-adapter checkout)
                                  (get checkout "commerce-adapter"))
                              str (str/replace #"^:" ""))
              checkout-amount (long (or (get-in checkout [:total :amount])
                                        (get-in checkout ["total" "amount"]) 0))]
          (cond
            (str/blank? (str recipient-email))
            {:status 400 :body {:error {:code "recipient-email-required"
                                        :message "A recipient email is required for seller fulfillment."}}}

            (str/blank? (str attempt-id))
            {:status 400 :body {:error {:code "attempt-id-required"
                                        :message "A stable attempt-id is required so session creation retries are idempotent."}}}

            (nil? batch)
            {:status 404 :body {:error {:code "checkout-batch-not-found"}}}

            (or (nil? checkout) (not (contains? group-ids (str checkout-id))))
            {:status 404 :body {:error {:code "payment-group-not-found"}}}

            (not= "pending-payment" status)
            {:status 409 :body {:error {:code "checkout-not-payable" :status status}}}

            (or (not= "seller-direct" funds-flow)
                (nil? (seller-commerce/adapter adapter)))
            {:status 409 :body {:error {:code "seller-commerce-adapter-not-configured"
                                        :adapter adapter}}}

            (and (not (zero? checkout-amount)) (not (payments-ready?)))
            {:status 503 :body {:error {:code "payments-unavailable"
                                        :message "Payments are temporarily unavailable. Your cart is saved."}}}

            :else
            (let [payment-details (query-checkout-payment-details game-name checkout-id)
                  orders (:orders payment-details)
                  seller-names (set (keep #(or (:seller %) (get % "seller")) orders))
                  seller-name (when (= 1 (count seller-names)) (first seller-names))
                  seller-resolution (when seller-name
                                      (resolve-seller-connect-accounts game-name [seller-name]))
                  seller-accounts (:accounts seller-resolution)
                  seller-account (or (get seller-accounts seller-name)
                                     (get seller-accounts (keyword (str seller-name))))
                  adapter-config (seller-commerce/adapter adapter)
                  connected-account-id (seller-commerce/stripe-account-id
                                        adapter-config seller-name seller-account)
                  caps (query-capabilities game-name buyer)
                  cap-index (into {} (map (fn [c]
                                            [(str (or (:id c) (get c "id"))) c])
                                          caps))
                  subscription-result (query-subscriptions-by-buyer-result game-name buyer)
                  subscriptions (:subscriptions subscription-result)
                  existing-subscription (some #(active-subscription-for-order
                                                subscriptions % caps)
                                              orders)
                  prepared (when (and (:ok? subscription-result)
                                      (not existing-subscription))
                             (prepare-seller-checkout
                              {:adapter adapter :checkout checkout :batch batch :buyer buyer
                               :recipient-email recipient-email :orders orders
                               :commission-bps (:commission-bps adapter-config)}))]
              (cond
                (not (:ok? payment-details))
                {:status 503 :body {:error {:code "checkout-payment-details-unavailable"
                                            :message "The checkout payment snapshot could not be loaded. Please retry."}}}

                (not= 1 (count seller-names))
                {:status 409 :body {:error {:code "seller-payment-group-invalid"
                                            :message "A seller-direct payment group must contain exactly one seller."}}}

                (not (:ok? subscription-result))
                {:status 503 :body {:error {:code "subscriptions-unavailable"
                                            :message "Could not verify your existing subscriptions. Please retry before paying."}}}

                existing-subscription
                {:status 409 :body {:error {:code "subscription-family-already-active"
                                            :message "You already have a subscription in this plan family. Change your plan from Purchases instead of starting a second subscription."
                                            :subscription-id (or (:id existing-subscription)
                                                                 (get existing-subscription "id"))}}}

                (not (:ok? seller-resolution))
                {:status 503 :body {:error {:code "seller-account-resolution-unavailable"
                                            :message "Seller payment account readiness could not be confirmed."}}}

                (and (pos? checkout-amount) (str/blank? (str connected-account-id)))
                {:status 409 :body {:error {:code "seller-account-not-ready"
                                            :message "The seller's current owner must connect a payment account before checkout."}}}

                (not (:ok? prepared))
                {:status (or (:status prepared) 502)
                 :body {:error {:code "seller-commerce-prepare-failed"
                                :adapter adapter
                                :details (:error prepared)}}}

                :else
                (let [application-fee (:application-fee-amount payment-details)
                      result (when (pos? checkout-amount)
                               (payments/create-seller-checkout-session
                              {:connected-account-id connected-account-id
                               :checkout-id (str checkout-id)
                               :checkout-batch-id (str batch-id)
                               :buyer buyer
                               :game-name game-name
                               :commerce-adapter adapter
                               :fulfillment-ref (:fulfillment-ref prepared)
                               :line-items (build-payment-line-items orders cap-index)
                               :application-fee-amount application-fee
                               :application-fee-bps (:commission-bps adapter-config)
                               :market-subscription-id (:market-subscription-id prepared)
                               :plan-sku (or (:external-sku (first orders))
                                             (get (first orders) "external-sku"))
                               :return-url (seller-checkout-return-url checkout-id)
                               :idempotency-key (str adapter ":" checkout-id ":" attempt-id)}))]
                  (if (zero? checkout-amount)
                    (let [provider-id (str "free:" adapter ":" checkout-id)
                          confirmed (verify-seller-commerce-payment
                                     {:checkout-id (str checkout-id)
                                      :checkout-batch-id (str batch-id)
                                      :buyer buyer :commerce-adapter adapter
                                      :fulfillment-ref (:fulfillment-ref prepared)
                                      :plan-sku (or (:external-sku (first orders))
                                                    (get (first orders) "external-sku"))
                                      :payment-provider "none" :payment-mode "none"
                                      :payment-status "free"
                                      :amount-total 0 :currency "USD"})
                          paid-resp (when (:ok? confirmed)
                                      (submit-service-event
                                       game-name "market/record-seller-checkout-paid"
                                       "game-master" ["game-master"]
                                       {:checkout-id (str checkout-id)
                                        :payment-ref {:platform "seller-commerce"
                                                      :kind "free"}}))
                          paid-result (or (get-in paid-resp [:body :gm-result])
                                          (get-in paid-resp [:body "gm-result"]))
                          started-resp (when (or (:success paid-result)
                                                (get paid-result "success"))
                                         (submit-service-event
                                          game-name "market/record-subscription-started"
                                          "game-master" ["game-master"]
                                          {:provider-subscription-id provider-id
                                           :market-subscription-id (:market-subscription-id prepared)
                                           :provider adapter :buyer buyer
                                           :checkout-id (str checkout-id)}))
                          started-result (or (get-in started-resp [:body :gm-result])
                                             (get-in started-resp [:body "gm-result"]))]
                      (if (and (:ok? confirmed)
                               (or (:success started-result) (get started-result "success")))
                        {:status 200
                         :body {:provider "none" :ui-mode "none" :status "activated"
                                :subscription-id (or (get-in started-result [:data :subscription-id])
                                                     (get-in started-result ["data" "subscription-id"]))
                                :recipient (:recipient prepared)}}
                        {:status 502
                         :body {:error {:code "free-subscription-activation-failed"
                                        :details (or (:error confirmed) started-result paid-result)}}}))
                    (if (:ok? result)
                    {:status 200
                     :body {:provider "stripe"
                            :ui-mode "embedded"
                            :connected-account connected-account-id
                            :session-id (:id result)
                            :client-secret (:client-secret result)
                            :expires-at (:expires-at result)
                            :recipient (:recipient prepared)}}
                    {:status 502
                     :body {:error {:code (:error-code result)
                                    :message (:error-message result)
                                    :details (:error result)}}}))))))))))
  (POST "/api/market/checkout-session" [:as request]
    (let [{:keys [context seat response]} (require-selected-seat request)]
      (cond
        response response
        :else
        (let [checkout-id (or (get-in request [:body :checkout-id])
                              (get-in request [:body :checkoutId]))
              game-name (:game-name context)
              buyer (:player-name seat)]
          (if (str/blank? (str checkout-id))
            {:status 400 :body {:error {:code "checkout-id-required"
                                        :message "Request body must include checkout-id"}}}
            (let [checkouts (query-checkouts-by-buyer game-name buyer)
                  checkout (->> checkouts
                                (filter #(= (str (or (:id %) (get % "id"))) (str checkout-id)))
                                first)
                status-norm (some-> (or (:status checkout) (get checkout "status"))
                                      str (str/replace #"^:" ""))
                provider-norm (some-> (or (:marketplace-provider checkout)
                                          (get checkout "marketplace-provider"))
                                      str (str/replace #"^:" ""))]
              (cond
                (nil? checkout)
                {:status 404 :body {:error {:code "checkout-not-found"
                                            :message "Checkout not found for this buyer"}}}

                (not= status-norm "pending-payment")
                {:status 409 :body {:error {:code "checkout-not-payable"
                                            :message "Checkout is not awaiting payment"
                                            :status status-norm}}}

                (= "seller-direct" (some-> (:funds-flow checkout) str (str/replace #"^:" "")))
                {:status 409 :body {:error {:code "seller-direct-checkout-required"}}}

                ;; Only the native Intergraph marketplace provider runs checkout +
                ;; payment through our native payment rail. External providers own the whole
                ;; checkout + payment flow themselves; Intergraph just records the
                ;; activity (ingestion is future work), so we create no native session.
                (not (contains? #{nil "" "intergraph"} provider-norm))
                {:status 409 :body {:error {:code "checkout-owned-by-marketplace-provider"
                                            :message "Checkout and payment for this checkout are owned by the external marketplace provider; they do not run through Intergraph's native payment rail."
                                            :marketplace-provider provider-norm}}}

                :else
                (let [order-ids (or (:orders checkout) (get checkout "orders") [])
                      all-orders (query-orders-by-buyer game-name buyer)
                      order-index (into {} (map (fn [o] [(str (or (:id o) (get o "id"))) o]) all-orders))
                      orders-for-checkout (->> order-ids (map #(get order-index (str %))) (remove nil?))
                      caps (query-capabilities game-name buyer)
                      cap-index (into {} (map (fn [c] [(str (or (:id c) (get c "id"))) c]) caps))
                      line-items (build-payment-line-items orders-for-checkout cap-index)
                      ;; games-as-merchants Model B: ask MoM whether this charge may
                      ;; name the game as merchant-of-record (on_behalf_of). Fail
                      ;; CLOSED — only a valid on-behalf-of decision proceeds; every
                      ;; other outcome rejects before Stripe is called.
                      ;;
                      ;; Only the stripe provider is gated. The stub provider creates
                      ;; no processor charge, so there is no merchant to attribute —
                      ;; and gating it would make local development impossible, since
                      ;; a dev MoM has no Connect account for the game.
                      stripe-provider? (= "stripe" (payments/active-provider-id))
                      attribution (cond
                                    (not (checkout-lines-match? checkout orders-for-checkout line-items))
                                    {:ok? false :response {:status 409 :body {:error {:code "checkout-total-mismatch"
                                      :message "Checkout contents could not be verified. Please refresh your purchase status."}}}}
                                    stripe-provider?
                                    (merchant-attribution
                                     game-name (fetch-game-merchant-of-record game-name))
                                    :else {:ok? true :on-behalf-of nil})]
                  (if-not (:ok? attribution)
                    (:response attribution)
                    (let [result (payments/create-checkout-session
                                  {:checkout-id checkout-id
                                   :buyer buyer
                                   :game-name game-name
                                   :line-items line-items
                                   :on-behalf-of (:on-behalf-of attribution)
                                   :success-url (success-url-template)
                                   :cancel-url (cancel-url-template)})]
                  (if (:ok? result)
                    {:status 200 :body (cond-> {:url (:url result)
                                                :provider (:provider result)}
                                         (:id result) (assoc :session-id (:id result))
                                         (:stub result) (assoc :stub true))}
                    {:status 502 :body {:error {:code (:error-code result)
                                                :message (:error-message result)
                                                :provider (:provider result)
                                                :details (:error result)}}})))))))))))
  (POST "/webhooks/stripe" [:as request]
    (let [raw-body (:stripe/raw-body request)
          sig (or (get-in request [:headers "stripe-signature"])
                  (get-in request [:headers "Stripe-Signature"]))]
      (try
       (payments/handle-stripe-webhook
       {:expected-game-name (current-game-name)
        :webhook-secrets [(gm-config/resolve-stripe-webhook-secret) (connect-webhook-secret)]
        :raw-body raw-body
        :signature sig
        :submit-service-event submit-service-event
        :fallback-game-name fallback-payment-game-name
        :verify-seller-payment verify-seller-commerce-payment
        :upsert-connect-account upsert-connect-account!
        :update-connect-account-by-external-id update-connect-account-by-external-id!
        :log-fn log!})
       (catch clojure.lang.ExceptionInfo e
         (if (= :wrong-game (:type (ex-data e)))
           {:status 200 :body {:ignored "another-game"}}
           (throw e))))))
  (GET "/api/market/purchases" [:as request]
    (let [{:keys [context seat response]} (require-selected-seat request)]
      (or response
          {:status 200 :body {:checkouts (query-checkouts-by-buyer (:game-name context) (:player-name seat))
                             :batches (query-checkout-batches-by-buyer (:game-name context) (:player-name seat))
                             :orders (query-orders-by-buyer (:game-name context) (:player-name seat))}})))
)

(defn wrap-stripe-raw-body
  "For POST /webhooks/stripe, copy the raw bytes into :stripe/raw-body before
   downstream middleware (wrap-json-body) consumes the InputStream. Stripe's
   HMAC verification needs the exact bytes."
  [handler]
  (fn [request]
    (if (and (= :post (:request-method request))
             (= "/webhooks/stripe" (:uri request)))
      (let [raw (slurp (:body request))]
        (handler (-> request
                     (assoc :stripe/raw-body raw)
                     (assoc :body (java.io.ByteArrayInputStream.
                                    (.getBytes ^String raw "UTF-8"))))))
      (handler request))))

(defn wrap-payment-readiness [handler]
  (fn [request]
    (if (and (= :post (:request-method request))
             (str/starts-with? (:uri request) "/api/market/")
             ;; This endpoint verifies the server-side checkout total and
             ;; activates zero-dollar seller subscriptions without Stripe.
             (not (re-matches #"/api/market/checkout-batches/[^/]+/checkouts/[^/]+/session"
                              (:uri request)))
             (not (payments-ready?)))
      {:status 503 :body {:error {:code "payments-unavailable"
                                 :message "Payments are temporarily unavailable. Your cart is saved."}}}
      (handler request))))
