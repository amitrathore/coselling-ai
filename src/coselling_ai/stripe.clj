(ns coselling-ai.stripe
  "Thin wrapper around Stripe REST API + webhook signature verification.
   v1 supports Hosted Checkout only; all funds land in our account. Connect
   migration is additive — see docs/stripe.md."
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)
           (java.security MessageDigest)
           (java.nio.charset StandardCharsets)
           (java.net URLEncoder)))

(def ^:private api-base "https://api.stripe.com")
(def ^:private connect-base "https://connect.stripe.com")

(defn oauth-authorize-url
  [{:keys [client-id redirect-uri state]}]
  (when (every? #(not (str/blank? (str %))) [client-id redirect-uri state])
    (str connect-base "/oauth/authorize?response_type=code&scope=read_write"
         "&client_id=" (URLEncoder/encode (str client-id) "UTF-8")
         "&redirect_uri=" (URLEncoder/encode (str redirect-uri) "UTF-8")
         "&state=" (URLEncoder/encode (str state) "UTF-8"))))

(defn exchange-oauth-code
  [{:keys [secret-key code]}]
  (if (or (str/blank? (str secret-key)) (str/blank? (str code)))
    {:ok? false :error-code "oauth-arguments-required"}
    (let [resp (http/post (str connect-base "/oauth/token")
                          {:form-params {"client_secret" secret-key
                                         "code" code
                                         "grant_type" "authorization_code"}
                           :as :json :throw-exceptions false})
          body (:body resp)]
      (if (= 200 (:status resp))
        {:ok? true :account-id (or (:stripe_user_id body) (get body "stripe_user_id"))}
        {:ok? false :status (:status resp) :error-code "oauth-exchange-failed"
         :error body}))))

(defn fetch-payment-intent
  "Fetch a PaymentIntent with its latest charge's balance transaction expanded.
   Returns {:ok? true :payment-intent <object>} on success, or
   {:ok? false :status ... :error ...} on failure. For a direct charge, pass
   connected-account-id so Stripe resolves the PaymentIntent in the seller's
   account rather than the platform account."
  [{:keys [secret-key payment-intent-id connected-account-id]}]
  (if (or (str/blank? (str secret-key)) (str/blank? (str payment-intent-id)))
    {:ok? false :reason :missing-args}
    (let [resp (http/get (str api-base "/v1/payment_intents/" payment-intent-id)
                         {:basic-auth [secret-key ""]
                          :headers (cond-> {}
                                     (not (str/blank? (str connected-account-id)))
                                     (assoc "Stripe-Account" (str connected-account-id)))
                          :query-params {"expand[]" "latest_charge.balance_transaction"}
                          :as :json
                          :throw-exceptions false})]
      (if (= 200 (:status resp))
        {:ok? true :payment-intent (:body resp)}
        {:ok? false :status (:status resp) :error (:body resp)}))))

(defn fetch-invoice-for-payment-intent
  "Resolve a subscription PaymentIntent through Stripe's Invoice Payments API.
   Recent Stripe API versions omit the legacy PaymentIntent.invoice field."
  [{:keys [secret-key payment-intent-id connected-account-id]}]
  (if (some #(str/blank? (str %))
            [secret-key payment-intent-id connected-account-id])
    {:ok? false :reason :missing-args}
    (let [auth {:basic-auth [secret-key ""]
                :headers {"Stripe-Account" (str connected-account-id)}
                :as :json :throw-exceptions false}
          listed (http/get (str api-base "/v1/invoice_payments")
                           (assoc auth :query-params
                                  {"payment[type]" "payment_intent"
                                   "payment[payment_intent]" (str payment-intent-id)
                                   "status" "paid" "limit" 2}))
          matches (get-in listed [:body :data])
          invoice-id (when (= 1 (count matches))
                       (or (:invoice (first matches))
                           (get (first matches) "invoice")))
          invoice (when (and (= 200 (:status listed))
                             (some? invoice-id)
                             (not (str/blank? (str invoice-id))))
                    (http/get (str api-base "/v1/invoices/" invoice-id) auth))]
      (if (= 200 (:status invoice))
        {:ok? true :invoice (:body invoice)}
        {:ok? false :status (or (:status invoice) (:status listed))
         :reason (if (> (count matches) 1) :ambiguous-invoice-payment
                     :invoice-not-found)}))))

(defn resolve-checkout-payment-intent
  "Resolve the original paid invoice of a seller-direct subscription Checkout.
   Stripe Checkout's payment_intent is nil for subscription mode, and recent
   Invoice objects no longer expose a legacy payment_intent field. Require one
   paid invoice payment matching the Market checkout gross before refunding."
  [{:keys [secret-key session-id connected-account-id checkout-id game-name amount]}]
  (if (or (some #(str/blank? (str %))
                [secret-key session-id connected-account-id checkout-id game-name])
          (not (pos-int? amount)))
    {:ok? false :reason :missing-args}
    (let [auth {:basic-auth [secret-key ""]
                :headers {"Stripe-Account" (str connected-account-id)}
                :as :json :throw-exceptions false}
          session-response (http/get (str api-base "/v1/checkout/sessions/" session-id) auth)
          session (:body session-response)
          metadata (or (:metadata session) (get session "metadata"))
          field (fn [m k] (or (get m k) (get m (name k))))
          invoice-id (field session :invoice)
          valid-session? (and (= 200 (:status session-response))
                              (= (str session-id) (field session :id))
                              (= "paid" (field session :payment_status))
                              (= "subscription" (field session :mode))
                              (= (str checkout-id) (field metadata :checkout-id))
                              (= (str game-name) (field metadata :game-name))
                              (= amount (field session :amount_total))
                              (not (str/blank? (str invoice-id))))
          listed (when valid-session?
                   (http/get (str api-base "/v1/invoice_payments")
                             (assoc auth :query-params
                                    {"invoice" (str invoice-id) "status" "paid"
                                     "limit" 2})))
          body (:body listed)
          payments (or (field body :data) [])
          payment (when (and (= 200 (:status listed))
                             (not (true? (field body :has_more)))
                             (= 1 (count payments)))
                    (first payments))
          payment-ref (field payment :payment)
          payment-intent-id (field payment-ref :payment_intent)]
      (if (and payment
               (= (str invoice-id) (field payment :invoice))
               (= "paid" (field payment :status))
               (= "payment_intent" (field payment-ref :type))
               (= amount (field payment :amount_paid))
               (not (str/blank? (str payment-intent-id))))
        {:ok? true :payment-intent-id payment-intent-id :invoice-id invoice-id}
        {:ok? false :status (or (:status listed) (:status session-response))
         :reason (if valid-session? :invoice-payment-not-unique
                     :checkout-payment-mismatch)}))))

(defn fetch-charge-refunds
  "Fetch the refund objects for a charge. Recent charge.refunded webhook
   objects omit the embedded refunds list, including for partial refunds."
  [{:keys [secret-key charge-id connected-account-id]}]
  (if (some #(str/blank? (str %)) [secret-key charge-id])
    {:ok? false :reason :missing-args}
    (let [resp (http/get (str api-base "/v1/charges/" charge-id "/refunds")
                         {:basic-auth [secret-key ""]
                          :headers (cond-> {}
                                     (not (str/blank? (str connected-account-id)))
                                     (assoc "Stripe-Account" (str connected-account-id)))
                          :query-params {"limit" 100}
                          :as :json :throw-exceptions false})
          body (:body resp)]
      (if (and (= 200 (:status resp))
               (not (or (:has_more body) (get body "has_more"))))
        {:ok? true :refunds (or (:data body) (get body "data") [])}
        {:ok? false :status (:status resp)
         :reason (if (or (:has_more body) (get body "has_more"))
                   :refund-list-truncated :refund-list-failed)}))))

(defn payment-intent-fee
  "Extract the actual Stripe fee from a fetched PaymentIntent object."
  [payment-intent]
  (let [bt (get-in payment-intent [:latest_charge :balance_transaction])]
    (when (some? (:fee bt))
      {:amount (:fee bt)
       :currency (str/upper-case (str (:currency bt)))})))

(defn payment-intent-on-behalf-of
  "The connected account Stripe treats as the settlement merchant for this charge.
   Returns the acct_ id string, or nil when the charge is platform-attributed.
   Stripe returns a bare id unless the field was expanded, so handle both."
  [payment-intent]
  (let [obo (:on_behalf_of payment-intent)]
    (cond
      (map? obo) (:id obo)
      (str/blank? (str obo)) nil
      :else (str obo))))

(defn payment-intent-available-on
  "The Unix timestamp (seconds) the charge's funds move from pending -> available
   in the platform balance, read from the latest charge's balance transaction.
   This is the maturity date a player's earnings from this charge become
   withdrawable. Returns nil when not yet known."
  [payment-intent]
  (get-in payment-intent [:latest_charge :balance_transaction :available_on]))

(defn- form-params-for-line-items
  "Stripe wants array params encoded as `line_items[0][price_data][...]`.
   clj-http's :form-params accepts nested vectors/maps; this builds a flat
   map of those keys.

   An item carrying `:recurring {:interval \"month\"}` adds
   `price_data[recurring][interval]`, which is what turns a line into a
   subscription line. Stripe requires the SESSION mode to match: recurring lines
   in a \"payment\" session are rejected outright, so the two are decided
   together in create-checkout-session."
  [line-items]
  (reduce
    (fn [acc [idx item]]
      (let [{:keys [title amount currency quantity recurring price-ref]} item
            k (fn [suffix] (str "line_items[" idx "][" suffix "]"))
            interval (some-> recurring :interval name)
            interval-count (:interval-count recurring)]
        (if (not (str/blank? (str price-ref)))
          (assoc acc (k "price") (str price-ref)
                     (k "quantity") (str (or quantity 1)))
          (cond-> (assoc acc
                         (k "price_data][currency") (str/lower-case (or currency "usd"))
                         (k "price_data][unit_amount") (str amount)
                         (k "price_data][product_data][name") (str (or title "Item"))
                         (k "quantity") (str (or quantity 1)))
            interval       (assoc (k "price_data][recurring][interval") interval)
            interval-count (assoc (k "price_data][recurring][interval_count")
                                  (str interval-count))))))
    {}
    (map-indexed vector line-items)))

(defn recurring-line-items?
  "True when every line carries :recurring. Mixed carts are refused upstream
   (market rejects them at checkout), so this is a total question: a session is
   either a subscription or a payment, never both."
  [line-items]
  (and (seq line-items) (every? #(some? (:recurring %)) line-items)))

(defn create-checkout-session
  "Creates a Stripe Hosted Checkout Session. Returns
   {:ok? true :id ... :url ...} on success, {:ok? false :status ... :error ...} otherwise.
   `line-items` is a vector of {:title :amount :currency :quantity} (amount is minor units).
   `game-name` is stamped into session metadata so the webhook can resolve the game.

   `on-behalf-of` is REQUIRED — the game's Stripe connected-account id. It names the
   game as merchant-of-record (games-as-merchants Model B); funds still settle to the
   platform (separate charges & transfers, no transfer_data). A blank value returns
   {:ok? false :error-code \"merchant-account-required\"} WITHOUT calling Stripe, so
   no session can exist for a charge the platform would be merchant of record for.

   The checkout route already refuses to proceed without a merchant account, but that
   is a guarantee about one caller. This is the structural one: no future caller can
   bypass it."
  [{:keys [secret-key checkout-id buyer game-name line-items success-url cancel-url on-behalf-of]}]
  (if (str/blank? (str on-behalf-of))
    {:ok? false
     :error-code "merchant-account-required"
     :error-message "A game merchant account (on_behalf_of) is required to create a Stripe checkout session."}
    (let [subscription? (recurring-line-items? line-items)
          params (merge {"mode" (if subscription? "subscription" "payment")
                         "success_url" success-url
                         "cancel_url" cancel-url
                         "metadata[checkout-id]" (str checkout-id)
                         "metadata[buyer]" (str buyer)
                         ;; Stamp the game so the webhook (which has no session) can
                         ;; route mark-checkout-paid to the right game for any game.
                         "metadata[game-name]" (str game-name)
                         ;; Stamp the merchant account on BOTH objects. The webhook
                         ;; compares this stamp against the PaymentIntent's actual
                         ;; on_behalf_of; its absence is what distinguishes a legacy
                         ;; pre-gate session from one that lost its attribution.
                         "metadata[merchant-account-id]" (str on-behalf-of)
                         "client_reference_id" (str checkout-id)}
                        (if subscription?
                          {}
                          {"payment_intent_data[metadata][checkout-id]" (str checkout-id)
                           "payment_intent_data[metadata][buyer]" (str buyer)
                           "payment_intent_data[metadata][game-name]" (str game-name)
                           "payment_intent_data[metadata][merchant-account-id]" (str on-behalf-of)
                           "payment_intent_data[on_behalf_of]" (str on-behalf-of)})
                        ;; A subscription session has NO payment_intent_data —
                        ;; Stripe rejects it — and every later invoice.paid
                        ;; arrives with neither a session nor the original
                        ;; PaymentIntent. So the routing metadata has to live on
                        ;; the SUBSCRIPTION, which each invoice does carry. Same
                        ;; reasoning as the existing metadata[game-name] stamp:
                        ;; the webhook has no context but what it is handed.
                        (if subscription?
                          {"subscription_data[metadata][buyer]" (str buyer)
                           "subscription_data[metadata][game-name]" (str game-name)
                           "subscription_data[metadata][checkout-id]" (str checkout-id)
                           "subscription_data[metadata][merchant-account-id]" (str on-behalf-of)
                           "subscription_data[on_behalf_of]" (str on-behalf-of)}
                          {})
                        (form-params-for-line-items line-items))
          resp (http/post (str api-base "/v1/checkout/sessions")
                          {:basic-auth [secret-key ""]
                           :form-params params
                           ;; Repeated clicks/reloads reuse one session for this checkout.
                           :headers {"Idempotency-Key" (str "coselling-checkout:" game-name ":" checkout-id)}
                           :as :json
                           :throw-exceptions false})
          body (:body resp)]
      (if (= 200 (:status resp))
        {:ok? true :id (:id body) :url (:url body)}
        {:ok? false :status (:status resp) :error body}))))

(defn create-embedded-direct-checkout-session
  "Creates an embedded, card-only Checkout Session as a direct charge on a
   Standard connected account. One-time payments use an application fee amount;
   subscriptions use an application fee percentage on every invoice.

   The client secret is returned to the authenticated caller but must never be
   persisted in Market state or logs. `idempotency-key` identifies one creation
   attempt and makes request retries safe."
  [{:keys [secret-key connected-account-id checkout-id checkout-batch-id buyer
           game-name commerce-adapter fulfillment-ref line-items application-fee-amount
           application-fee-bps market-subscription-id plan-sku return-url idempotency-key]}]
  (cond
    (str/blank? (str connected-account-id))
    {:ok? false :error-code "seller-stripe-account-required"
     :error-message "The seller's connected Stripe account is required."}

    (not (or (pos? (long (or application-fee-amount 0)))
             (pos? (long (or application-fee-bps 0)))))
    {:ok? false :error-code "application-fee-required"
     :error-message "A positive application fee rate is required."}

    :else
    (let [subscription? (recurring-line-items? line-items)
          metadata {"checkout-id" checkout-id
                    "checkout-batch-id" checkout-batch-id
                    "buyer" buyer
                    "game-name" game-name
                    "commerce-adapter" commerce-adapter
                    "fulfillment-ref" fulfillment-ref
                    "market-subscription-id" market-subscription-id
                    "plan-sku" plan-sku}
          metadata-params (reduce-kv
                           (fn [m k v]
                             (if (str/blank? (str v))
                               m
                               (cond-> (assoc m (str "metadata[" k "]") (str v))
                                 subscription?
                                 (assoc (str "subscription_data[metadata][" k "]") (str v))
                                 (not subscription?)
                                 (assoc (str "payment_intent_data[metadata][" k "]") (str v)))))
                           {}
                           metadata)
          params (merge {"mode" (if subscription? "subscription" "payment")
                         ;; Stripe renamed the prebuilt embedded Checkout mode
                         ;; to `embedded_page`. Sending the former `embedded`
                         ;; value now fails before a Session is created.
                         "ui_mode" "embedded_page"
                         "return_url" return-url
                         "payment_method_types[0]" "card"
                         "client_reference_id" (str checkout-id)}
                        (if subscription?
                          {"subscription_data[application_fee_percent]"
                           (str (/ (double application-fee-bps) 100.0))}
                          {"payment_intent_data[application_fee_amount]"
                           (str application-fee-amount)})
                        metadata-params
                        (form-params-for-line-items line-items))
          resp (http/post (str api-base "/v1/checkout/sessions")
                          {:basic-auth [secret-key ""]
                           :headers (cond-> {"Stripe-Account" (str connected-account-id)}
                                      (not (str/blank? (str idempotency-key)))
                                      (assoc "Idempotency-Key" (str idempotency-key)))
                           :form-params params
                           :as :json
                           :throw-exceptions false})
          body (:body resp)]
      (if (= 200 (:status resp))
        {:ok? true
         :id (:id body)
         :client-secret (:client_secret body)
         :expires-at (:expires_at body)}
        {:ok? false :status (:status resp) :error body}))))

(defn expire-checkout-session
  "Expires an open Checkout Session so it can no longer be paid.
   POST /v1/checkout/sessions/{id}/expire.

   Used to drain sessions created before the merchant gate shipped: those carry
   no merchant-account-id stamp, so if one completes afterwards the webhook has
   nothing to verify against and must grandfather it. Expiring them narrows that
   window. Returns {:ok? true :status \"expired\"} or {:ok? false :status ... :error ...}."
  [{:keys [secret-key session-id]}]
  (if (or (str/blank? (str secret-key)) (str/blank? (str session-id)))
    {:ok? false :reason :missing-args}
    (let [resp (http/post (str api-base "/v1/checkout/sessions/" session-id "/expire")
                          {:basic-auth [secret-key ""]
                           :as :json
                           :throw-exceptions false})]
      (if (= 200 (:status resp))
        {:ok? true :id (get-in resp [:body :id]) :status (get-in resp [:body :status])}
        {:ok? false :status (:status resp) :error (:body resp)}))))

(defn list-open-checkout-sessions
  "Lists OPEN Checkout Sessions, newest first. GET /v1/checkout/sessions?status=open.

   Only open sessions can be expired, so the filter is not a convenience — a
   completed or already-expired session would 400 on expire. Returns
   {:ok? true :sessions [...] :has-more? bool}; page with :starting-after using
   the last id from the previous page."
  [{:keys [secret-key starting-after limit]}]
  (if (str/blank? (str secret-key))
    {:ok? false :reason :missing-args}
    (let [resp (http/get (str api-base "/v1/checkout/sessions")
                         {:basic-auth [secret-key ""]
                          :query-params (cond-> {"status" "open"
                                                 "limit" (str (or limit 100))}
                                          (not (str/blank? (str starting-after)))
                                          (assoc "starting_after" (str starting-after)))
                          :as :json
                          :throw-exceptions false})]
      (if (= 200 (:status resp))
        {:ok? true
         :sessions (vec (get-in resp [:body :data]))
         :has-more? (boolean (get-in resp [:body :has_more]))}
        {:ok? false :status (:status resp) :error (:body resp)}))))

(defn retrieve-subscription
  [{:keys [secret-key connected-account-id subscription-id]}]
  (let [response (http/get (str api-base "/v1/subscriptions/" subscription-id)
                           {:basic-auth [secret-key ""]
                            :headers {"Stripe-Account" (str connected-account-id)}
                            :as :json :throw-exceptions false})]
    (if (= 200 (:status response))
      {:ok? true :subscription (:body response)}
      {:ok? false :status (:status response) :error (:body response)})))

(defn create-customer-portal-session
  "Create Stripe's hosted billing portal in the seller's connected account."
  [{:keys [secret-key connected-account-id customer-id return-url
           subscription-id subscription-item-id target-price-id]}]
  (let [response (http/post (str api-base "/v1/billing_portal/sessions")
                            {:basic-auth [secret-key ""]
                             :headers {"Stripe-Account" (str connected-account-id)}
                             :form-params (cond-> {"customer" (str customer-id)
                                                   "return_url" (str return-url)}
                                            (and subscription-id subscription-item-id
                                                 target-price-id)
                                            (assoc "flow_data[type]" "subscription_update_confirm"
                                                   "flow_data[subscription_update_confirm][subscription]"
                                                   (str subscription-id)
                                                   "flow_data[subscription_update_confirm][items][0][id]"
                                                   (str subscription-item-id)
                                                   "flow_data[subscription_update_confirm][items][0][price]"
                                                   (str target-price-id)))
                             :as :json :throw-exceptions false})]
    (if (= 200 (:status response))
      {:ok? true :url (get-in response [:body :url])}
      {:ok? false :status (:status response) :error (:body response)})))

;; --- Connect (Express payout accounts + transfers) ---

(defn create-connected-account
  "Creates a Stripe Express connected account for a player's payouts. `owner-id`
   and `game-name` are stamped into metadata so the account.updated webhook can
   resolve the account back to a (owner, game) payout record via the connect
   endpoint. Returns {:ok? true :id \"acct_...\"} or {:ok? false :status ...}."
  [{:keys [secret-key owner-id game-name email house-account]}]
  (let [house? (not (str/blank? (str house-account)))
        params (cond-> {"type" "express"
                        "capabilities[transfers][requested]" "true"}
                 ;; house (treasury) account: stamp the house account name so
                 ;; account.updated resolves the global house payout record
                 house? (assoc "metadata[account]" (str house-account))
                 (not house?) (assoc "metadata[owner-id]" (str owner-id)
                                     "metadata[game-name]" (str game-name))
                 (not (str/blank? (str email))) (assoc "email" (str email)))
        resp (http/post (str api-base "/v1/accounts")
                        {:basic-auth [secret-key ""]
                         :form-params params
                         :as :json
                         :throw-exceptions false})
        body (:body resp)]
    (if (= 200 (:status resp))
      {:ok? true :id (:id body)}
      {:ok? false :status (:status resp) :error body})))

(defn create-account-link
  "Creates a hosted Account Link so a player can complete Express onboarding.
   Returns {:ok? true :url ...} or {:ok? false :status ... :error ...}."
  [{:keys [secret-key account-id refresh-url return-url]}]
  (let [resp (http/post (str api-base "/v1/account_links")
                        {:basic-auth [secret-key ""]
                         :form-params {"account" (str account-id)
                                       "type" "account_onboarding"
                                       "refresh_url" refresh-url
                                       "return_url" return-url}
                         :as :json
                         :throw-exceptions false})
        body (:body resp)]
    (if (= 200 (:status resp))
      {:ok? true :url (:url body)}
      {:ok? false :status (:status resp) :error body})))

(defn retrieve-account
  "Fetches a connected account to read payouts_enabled / requirements. Returns
   {:ok? true :account <object>} or {:ok? false :status ... :error ...}."
  [{:keys [secret-key account-id]}]
  (let [resp (http/get (str api-base "/v1/accounts/" account-id)
                       {:basic-auth [secret-key ""]
                        :as :json
                        :throw-exceptions false})]
    (if (= 200 (:status resp))
      {:ok? true :account (:body resp)}
      {:ok? false :status (:status resp) :error (:body resp)})))

(defn create-transfer
  "Transfers `amount` (minor units) from the platform balance to a connected
   account (separate charges & transfers model). `withdrawal-id` is passed as the
   Stripe Idempotency-Key header AND stamped into metadata so transfer.* webhooks
   correlate back to the withdrawal. Returns {:ok? true :id \"tr_...\"} on success.
   On failure returns {:ok? false :status ... :error ... :error-code <stripe code>};
   a short platform balance surfaces as error-code \"balance_insufficient\"."
  [{:keys [secret-key amount currency destination withdrawal-id game-name player-name]}]
  (let [params {"amount" (str amount)
                "currency" (str/lower-case (or currency "usd"))
                "destination" (str destination)
                "transfer_group" (str "withdrawal:" withdrawal-id)
                "metadata[withdrawal-id]" (str withdrawal-id)
                "metadata[game-name]" (str game-name)
                "metadata[player-name]" (str player-name)}
        resp (http/post (str api-base "/v1/transfers")
                        {:basic-auth [secret-key ""]
                         :headers {"Idempotency-Key" (str withdrawal-id)}
                         :form-params params
                         :as :json
                         :throw-exceptions false})
        body (:body resp)]
    (if (= 200 (:status resp))
      {:ok? true :id (:id body)}
      {:ok? false
       :status (:status resp)
       :error body
       :error-code (or (get-in body [:error :code]) (get-in body ["error" "code"]))})))

(defn create-refund
  "Refunds `amount` (minor units) of a PaymentIntent back to the buyer. Omit
   `amount` for a full refund. `refund-id` is passed as the Stripe
   Idempotency-Key header AND stamped into metadata, so a retried refund never
   double-refunds and a `charge.refunded` webhook correlates straight back to
   the market refund record.

   Model B note: charges are created with `on_behalf_of` and no `transfer_data`,
   so the funds sit in the Intergraph platform balance and a plain platform-key
   refund is correct — no `refund_application_fee` / `reverse_transfer`. A short
   platform balance surfaces as error-code \"balance_insufficient\".

   Returns {:ok? true :id \"re_...\" :status <stripe status>} on success, or
   {:ok? false :status ... :error ... :error-code <stripe code>}."
  [{:keys [secret-key payment-intent-id amount reason refund-id order-id checkout-id game-name
           connected-account-id]}]
  (let [params (cond-> {"payment_intent" (str payment-intent-id)
                        "metadata[refund-id]" (str refund-id)
                        "metadata[order-id]" (str order-id)
                        "metadata[checkout-id]" (str checkout-id)
                        "metadata[game-name]" (str game-name)}
                 amount (assoc "amount" (str amount))
                 ;; Stripe only accepts its own enum here; anything else must
                 ;; ride along in metadata rather than being rejected.
                 (contains? #{"duplicate" "fraudulent" "requested_by_customer"} reason)
                 (assoc "reason" reason)
                 (and reason
                      (not (contains? #{"duplicate" "fraudulent" "requested_by_customer"} reason)))
                 (assoc "metadata[reason]" (str reason)))
        resp (http/post (str api-base "/v1/refunds")
                        {:basic-auth [secret-key ""]
                         :headers (cond-> {"Idempotency-Key" (str refund-id)}
                                    (not (str/blank? (str connected-account-id)))
                                    (assoc "Stripe-Account" (str connected-account-id)))
                         :form-params params
                         :as :json
                         :throw-exceptions false})
        body (:body resp)]
    (if (= 200 (:status resp))
      {:ok? true :id (:id body) :status (:status body)}
      {:ok? false
       :status (:status resp)
       :error body
       :error-code (or (get-in body [:error :code]) (get-in body ["error" "code"]))})))

(defn refund-application-fee
  "Return part of a direct charge's application fee to its connected seller.
   The Market refund id is the idempotency key. This is separate from the buyer
   refund and always runs on the platform account."
  [{:keys [secret-key payment-intent-id connected-account-id amount refund-id]}]
  (let [payment (fetch-payment-intent {:secret-key secret-key
                                       :payment-intent-id payment-intent-id
                                       :connected-account-id connected-account-id})
        fee-id (get-in payment [:payment-intent :latest_charge :application_fee])]
    (if (or (not (:ok? payment)) (str/blank? (str fee-id)))
      {:ok? false :error-code "application-fee-not-found"}
      (let [resp (http/post (str api-base "/v1/application_fees/" fee-id "/refunds")
                            {:basic-auth [secret-key ""]
                             :headers {"Idempotency-Key" (str "fee-refund:" refund-id)}
                             :form-params {"amount" (str amount)
                                           "metadata[market-refund-id]" (str refund-id)}
                             :as :json :throw-exceptions false})
            body (:body resp)]
        (if (= 200 (:status resp))
          {:ok? true :id (:id body) :amount (:amount body)}
          {:ok? false :status (:status resp)
           :error-code (or (get-in body [:error :code])
                           "application-fee-refund-failed")
           :error body})))))

(defn fetch-refund
  "Reads a refund back from Stripe. Used by the recovery path for refunds left
   :reserved because the process died before/while calling Stripe."
  [{:keys [secret-key refund-id]}]
  (let [resp (http/get (str api-base "/v1/refunds/" refund-id)
                       {:basic-auth [secret-key ""]
                        :as :json
                        :throw-exceptions false})]
    (if (= 200 (:status resp))
      {:ok? true :refund (:body resp)}
      {:ok? false :status (:status resp) :error (:body resp)})))

(defn fetch-payment-fee
  "Resolve the actual Stripe processing fee for a completed PaymentIntent by
   expanding its latest charge's balance transaction. Funds land net of this fee
   in our account, so it is the platform's real cost of the sale.

   Returns {:ok? true :amount <minor-units> :currency <ISO>} on success, or
   {:ok? false :status :error} so callers can degrade gracefully (no fee
   recorded → platform simply absorbs it, as before)."
  [{:keys [secret-key payment-intent-id]}]
  (let [resp (fetch-payment-intent {:secret-key secret-key
                                    :payment-intent-id payment-intent-id})
        fee (payment-intent-fee (:payment-intent resp))]
    (if (:ok? resp)
      (if fee
        {:ok? true
         :amount (:amount fee)
         :currency (:currency fee)}
        {:ok? false :reason :missing-fee :status 200})
      resp)))

(defn- hmac-sha256-hex
  [secret payload]
  (let [mac (Mac/getInstance "HmacSHA256")
        key-bytes (.getBytes ^String secret StandardCharsets/UTF_8)
        spec (SecretKeySpec. key-bytes "HmacSHA256")
        _ (.init mac spec)
        bytes (.doFinal mac (.getBytes ^String payload StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) bytes))))

(defn- parse-signature-header
  "Stripe signature header: `t=12345,v1=abcd,v1=efgh,v0=...`. Returns
   {:timestamp 12345 :v1 #{...}}."
  [header]
  (when (string? header)
    (reduce
      (fn [acc kv]
        (let [[k v] (str/split kv #"=" 2)]
          (cond
            (= k "t")  (assoc acc :timestamp (try (Long/parseLong v) (catch Exception _ nil)))
            (= k "v1") (update acc :v1 (fnil conj #{}) v)
            :else acc)))
      {:v1 #{}}
      (str/split header #","))))

(defn- constant-time-eq?
  [a b]
  (MessageDigest/isEqual (.getBytes ^String a StandardCharsets/UTF_8)
                         (.getBytes ^String b StandardCharsets/UTF_8)))

(def ^:private signature-tolerance-seconds 300)

(defn verify-signature
  "Returns {:ok? true} when the Stripe-Signature header validates `raw-body`
   against `secret`, else {:ok? false :reason :missing|:malformed|:expired|:mismatch}.
   `now-seconds` defaults to current wall clock; pass explicitly to test."
  ([raw-body sig-header secret]
   (verify-signature raw-body sig-header secret (quot (System/currentTimeMillis) 1000)))
  ([raw-body sig-header secret now-seconds]
   (cond
     (or (str/blank? raw-body) (str/blank? sig-header) (str/blank? secret))
     {:ok? false :reason :missing}

     :else
     (let [{:keys [timestamp v1]} (parse-signature-header sig-header)]
       (cond
         (or (nil? timestamp) (empty? v1))
         {:ok? false :reason :malformed}

         (> (Math/abs (long (- now-seconds timestamp))) signature-tolerance-seconds)
         {:ok? false :reason :expired}

         :else
         (let [expected (hmac-sha256-hex secret (str timestamp "." raw-body))]
           (if (some #(constant-time-eq? expected %) v1)
             {:ok? true}
             {:ok? false :reason :mismatch})))))))

(defn parse-event
  "Parse a raw Stripe webhook JSON body into a Clojure map with keyword keys."
  [raw-body]
  (try
    (json/parse-string raw-body true)
    (catch Exception _ nil)))
