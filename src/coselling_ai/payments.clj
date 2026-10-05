(ns coselling-ai.payments
  "Market checkout payment-provider adapters.

   This is intentionally scoped to native Market checkout collection. It is not
   the general Payments Protocol; a future protocol can replace this namespace
   as the thing Market calls to collect a checkout."
  (:require [clojure.string :as str]
            [game.gm.config :as gm-config]
            [coselling-ai.seller-commerce :as seller-commerce]
            [coselling-ai.stripe :as stripe]))

(def provider-ids #{"stripe" "stub"})

(defn- resolve-market-payment-provider
  []
  (let [v (System/getenv "MARKET_PAYMENT_PROVIDER")]
    (when-not (str/blank? v) v)))

(defn configured-provider-id
  []
  (let [raw (some-> (resolve-market-payment-provider) str/lower-case)]
    (cond
      (contains? provider-ids raw) raw
      (gm-config/stripe-enabled?) "stripe"
      :else "stub")))

(defn active-provider-id
  []
  (let [configured (configured-provider-id)]
    (if (and (= configured "stripe") (not (gm-config/stripe-enabled?)))
      "stub"
      configured)))

(defn assert-provider-configured!
  "Fails fast when the configured payment provider cannot actually be honoured.

   `active-provider-id` silently downgrades an explicitly configured \"stripe\" to
   \"stub\" when no Stripe key is set. A deployment that asked for Stripe would then
   produce fake successful checkouts: the buyer sees success, no money moves, and
   nothing anywhere reports an error. That is a worse failure than refusing to
   start, so this throws.

   Only the divergence is an error. Explicitly choosing stub, or leaving the whole
   thing unconfigured in local dev, is a deliberate choice and passes silently.
   Returns nil when the configuration is coherent."
  []
  (let [configured (configured-provider-id)
        active (active-provider-id)]
    (when (and (= "stripe" configured) (= "stub" active))
      (throw (ex-info (str "Market payment provider is configured as \"stripe\" but no Stripe "
                           "key is set, so checkouts would silently run in stub mode. "
                           "Set STRIPE_SECRET_KEY, or set MARKET_PAYMENT_PROVIDER=stub to "
                           "run without a payment processor deliberately.")
                      {:reason :provider-misconfigured
                       :configured configured
                       :active active})))
    nil))

(defn payment-mode
  []
  (let [provider (active-provider-id)]
    (cond-> {:mode provider
             :provider provider}
      (and (= "stripe" provider) (gm-config/resolve-stripe-publishable-key))
      (assoc :publishable-key (gm-config/resolve-stripe-publishable-key)))))

(defn create-checkout-session
  [{:keys [provider-id checkout-id buyer game-name line-items success-url cancel-url on-behalf-of]}]
  (let [provider (or provider-id (active-provider-id))]
    (case provider
      "stripe"
      (let [result (stripe/create-checkout-session
                    {:secret-key (gm-config/resolve-stripe-secret-key)
                     :checkout-id checkout-id
                     :buyer buyer
                     :game-name game-name
                     :line-items line-items
                     :success-url success-url
                     :cancel-url cancel-url
                     :on-behalf-of on-behalf-of})]
        (if (:ok? result)
          (assoc result :provider "stripe")
          ;; Preserve an error code the adapter set deliberately (e.g.
          ;; merchant-account-required, which never reached Stripe). Flattening
          ;; everything to stripe-session-create-failed would report a refusal we
          ;; made ourselves as a Stripe rejection, which is both wrong and
          ;; undiagnosable.
          (assoc result
                 :provider "stripe"
                 :error-code (or (:error-code result) "stripe-session-create-failed")
                 :error-message (or (:error-message result)
                                    "Stripe rejected the session create call"))))

      "stub"
      {:ok? true
       :provider "stub"
       :stub true
       :url nil})))

(defn create-seller-checkout-session
  "Creates a seller-owned payment group. This intentionally has no
   stub fallback: reporting a paid credit purchase when no direct charge exists
   would create unbacked credits."
  [{:keys [connected-account-id checkout-id checkout-batch-id buyer game-name commerce-adapter
           fulfillment-ref line-items application-fee-amount application-fee-bps
           market-subscription-id plan-sku return-url idempotency-key]}]
  (let [result (stripe/create-embedded-direct-checkout-session
                {:secret-key (gm-config/resolve-stripe-secret-key)
                 :connected-account-id connected-account-id
                 :checkout-id checkout-id
                 :checkout-batch-id checkout-batch-id
                 :buyer buyer
                 :game-name game-name
                 :commerce-adapter commerce-adapter
                 :fulfillment-ref fulfillment-ref
                 :line-items line-items
                 :application-fee-amount application-fee-amount
                 :application-fee-bps application-fee-bps
                 :market-subscription-id market-subscription-id
                 :plan-sku plan-sku
                 :return-url return-url
                 :idempotency-key idempotency-key})]
    (if (:ok? result)
      (assoc result :provider "stripe" :ui-mode "embedded")
      (assoc result
             :provider "stripe"
             :error-code (or (:error-code result) "stripe-session-create-failed")
             :error-message (or (:error-message result)
                                "Stripe rejected the seller checkout session")))))

(defn create-connected-account [opts]
  (stripe/create-connected-account (assoc opts :secret-key (gm-config/resolve-stripe-secret-key))))

(defn create-account-link [opts]
  (stripe/create-account-link (assoc opts :secret-key (gm-config/resolve-stripe-secret-key))))

(defn create-transfer [opts]
  (stripe/create-transfer (assoc opts :secret-key (gm-config/resolve-stripe-secret-key))))

(defn create-refund [opts]
  (stripe/create-refund (assoc opts :secret-key (gm-config/resolve-stripe-secret-key))))

(defn start-seller-connection
  [{:keys [client-id redirect-uri state]}]
  (if-let [url (stripe/oauth-authorize-url {:client-id client-id :redirect-uri redirect-uri :state state})]
    {:ok? true :provider "stripe" :url url}
    {:ok? false :provider "stripe" :error-code "connect-configuration-invalid"}))

(defn complete-seller-connection
  [{:keys [code]}]
  (let [secret-key (gm-config/resolve-stripe-secret-key)
        exchange (stripe/exchange-oauth-code
                  {:secret-key secret-key :code code})]
    (if-not (:ok? exchange)
      (assoc exchange :provider "stripe")
      (let [retrieved (stripe/retrieve-account
                       {:secret-key secret-key
                        :account-id (:account-id exchange)})
            account (:account retrieved)
            caps (:capabilities account)
            card-payments (or (:card_payments caps) (get caps "card_payments"))
            transfers (or (:transfers caps) (get caps "transfers"))
            standard? (= "standard" (some-> (or (:type account) (get account "type")) str/lower-case))
            ;; Stripe OAuth's test-only account shortcuts return several unstable
            ;; requirements shapes while leaving readiness flags false. They still
            ;; permit direct test charges and application fees. OAuth proves the
            ;; account belongs to this platform; retain the Standard-account check
            ;; and bypass readiness only under a test key. Live mode stays strict.
            test-mode-readiness-override? (and (str/starts-with? (str secret-key) "sk_test_")
                                               standard?)
            payment-ready? (or test-mode-readiness-override?
                               (and standard?
                                    (true? (or (:charges_enabled account) (get account "charges_enabled")))
                                    (= "active" card-payments)))
            payout-ready? (or test-mode-readiness-override?
                              (and (true? (or (:payouts_enabled account) (get account "payouts_enabled")))
                                   (= "active" transfers)))]
        (if (and (:ok? retrieved) payment-ready? payout-ready?)
          {:ok? true :provider "stripe" :external-id (:account-id exchange)
           :account-type "standard" :payments-enabled true :payouts-enabled true
           :provider-details {:country (or (:country account) (get account "country"))
                              :default-currency (or (:default_currency account) (get account "default_currency"))
                              :card-payments card-payments :transfers transfers
                              :test-mode-readiness-override test-mode-readiness-override?}}
          {:ok? false :provider "stripe" :error-code "seller-account-not-ready"
           :status (:status retrieved)})))))

(defn- session-metadata
  [session]
  (or (:metadata session) (get session "metadata")))

(defn- metadata-value
  [metadata k]
  (or (get metadata k) (get metadata (name k))))

(defn- object-value
  [object k]
  (or (get object k) (get object (name k))))

(defn- promotion-code-id
  "Best-effort audit identifier for an applied Stripe promotion code. Stripe may
   return the promotion_code reference as either a bare id or an expanded map."
  [object]
  (let [discounts (or (object-value object :discounts) [])
        discount (first discounts)
        promotion-code (when (map? discount)
                         (object-value discount :promotion_code))]
    (cond
      (string? promotion-code) promotion-code
      (map? promotion-code) (object-value promotion-code :id)
      :else nil)))

(defn- discount-amount
  "Return a provider-reported aggregate discount in minor units when available.
   Checkout Sessions expose total_details.amount_discount; invoices expose a
   vector of total_discount_amounts."
  [object]
  (let [total-details (object-value object :total_details)
        checkout-discount (when (map? total-details)
                            (object-value total-details :amount_discount))
        invoice-discounts (object-value object :total_discount_amounts)]
    (cond
      (integer? checkout-discount) checkout-discount
      (sequential? invoice-discounts)
      (reduce + 0 (keep (fn [entry]
                          (let [amount (when (map? entry) (object-value entry :amount))]
                            (when (integer? amount) amount)))
                        invoice-discounts))
      :else nil)))

(defn- payment-ref-with-gross
  "Build the canonical gross-charge fields used by Market's payout eligibility
   assessment. Processing fees and balance-transaction net are intentionally
   separate and cannot influence that assessment."
  [base object amount-key]
  (let [amount (object-value object amount-key)
        currency (object-value object :currency)
        discount (discount-amount object)
        promotion-id (promotion-code-id object)]
    (cond-> base
      (and (integer? amount) (not (str/blank? (str currency))))
      (assoc :amount {:amount amount
                      :currency (str/upper-case (str currency))})

      (and (integer? discount) (not (str/blank? (str currency))))
      (assoc :discount {:amount discount
                        :currency (str/upper-case (str currency))})

      promotion-id
      (assoc :promotion-code-id promotion-id))))

(defn- gm-result
  [resp]
  (let [body (:body resp)]
    (or (:gm-result body) (get body "gm-result"))))

(defn- gm-error-code
  [result]
  (or (get-in result [:error :code])
      (get-in result ["error" "code"])))

(defn- external-refund-record!
  "Reserve an external refund, then settle its ledger leg in a top-level GM
   event. MoM deliberately ignores effects returned by ledger callbacks."
  [submit-service-event game-name request]
  (let [recorded (submit-service-event game-name "market/record-external-refund"
                                       "game-master" ["game-master"] request)
        result (gm-result recorded)
        success? (or (:success result) (get result "success"))
        effect-results (or (get-in recorded [:body :effect-results])
                           (get-in recorded [:body "effect-results"]) [])
        effects-ok? (every? #(= "succeeded"
                                (some-> (or (:status %) (get % "status"))
                                        str (str/replace #"^:" "")))
                            effect-results)
        refunds (or (get-in result [:data :refunds])
                    (get-in result ["data" "refunds"]) [])]
    (if-not (and success? effects-ok?)
      {:ok? false :recorded recorded}
      (let [settlements
            (for [refund refunds
                  :let [field (fn [k] (or (get refund k) (get refund (name k))))
                        status (some-> (field :status) str (str/replace #"^:" ""))]
                  :when (contains? #{"reserving" "reserved" "settling"} status)]
              (submit-service-event game-name "market/mark-refund-settled"
                                    "game-master" ["game-master"]
                                    {:order-id (field :order-id)
                                     :refund-id (field :refund-id)
                                     :stripe-refund-id (:stripe-refund-id request)}))]
        {:ok? (every? (fn [response]
                        (let [r (gm-result response)
                              effects (or (get-in response [:body :effect-results])
                                          (get-in response [:body "effect-results"]) [])]
                          (and (or (:success r) (get r "success"))
                               (every? #(= "succeeded"
                                           (some-> (or (:status %) (get % "status"))
                                                   str (str/replace #"^:" "")))
                                       effects))))
                      settlements)
         :recorded recorded :refunds refunds :settlements settlements}))))

(defn- relayed-conflict?
  "True when a GM 409 reached us with its error code stripped.

   MoM relays a GM's HTTP 409 as a flat {\"error\":\"handler-call-failed\",
   \"message\":\"clj-http: status 409\"} — no :code, nothing to match on. Every
   409 this webhook can provoke means \"already done\": the checkout is paid, the
   slug exists, the refund settled. Treating those as failures returns 5xx and
   Stripe retries a settled fact for days.

   Observed twice in one day: market-seed logged every idempotent re-seed as a
   rejection, and a redelivered checkout.session.completed answered 500 for an
   already-paid checkout."
  [resp result]
  (let [s (str (:body resp) (pr-str result))]
    (boolean (re-find #"status 409" s))))

(defn- native-stripe-session-completed-response
  [{:keys [event-id session submit-service-event fallback-game-name log-fn]}]
  (let [metadata (session-metadata session)
        checkout-id (metadata-value metadata :checkout-id)
        buyer (metadata-value metadata :buyer)
        game-name (or (metadata-value metadata :game-name) (fallback-game-name))
        ;; A subscription session settles through an INVOICE, not a
        ;; PaymentIntent, so :payment_intent is nil. Fetching nil would fail and
        ;; be logged as a lost fee. Its merchant stamp lives on the subscription
        ;; (subscription_data[metadata]) instead, which is also what every later
        ;; invoice carries.
        subscription-id (or (:subscription session) (get session "subscription"))
        subscription? (not (str/blank? (str subscription-id)))
        market-subscription-id (metadata-value metadata :market-subscription-id)
        payment-intent-result (when-not subscription?
                                (stripe/fetch-payment-intent
                                 {:secret-key (gm-config/resolve-stripe-secret-key)
                                  :payment-intent-id (:payment_intent session)}))
        payment-intent (:payment-intent payment-intent-result)
        fee (stripe/payment-intent-fee payment-intent)
        available-on (stripe/payment-intent-available-on payment-intent)
        ;; Merchant attribution check. Free: the PaymentIntent is already fetched
        ;; above for fee/available_on, so this costs no extra Stripe call.
        ;;
        ;; `stamped` is what we asked for when creating the session (§3 writes it
        ;; into PaymentIntent metadata); `attributed` is what Stripe actually did.
        ;; The rule keys on the STAMP, not on on_behalf_of — a session created
        ;; before this gate shipped has neither, and quarantining it would charge
        ;; the buyer and then strand the order with no way to complete it.
        pi-metadata (or (:metadata payment-intent) (get payment-intent "metadata"))
        stamped (metadata-value pi-metadata :merchant-account-id)
        attributed (stripe/payment-intent-on-behalf-of payment-intent)
        stamped? (not (str/blank? (str stamped)))
        attribution-ok? (or (not stamped?) (= (str stamped) (str attributed)))
        payment-ref (cond-> (payment-ref-with-gross
                              {:platform "stripe"
                               :session-id (:id session)
                               :payment-intent-id (:payment_intent session)
                               :stripe-event-id event-id}
                              session :amount_total)
                      fee (assoc :fee fee)
                      available-on (assoc :available-on available-on)
                      ;; Recorded for audit, refunds and reconciliation.
                      attributed (assoc :merchant-account-id (str attributed)))]
    (when (and (not fee) (not subscription?))
      ;; Two very different conditions used to share one WARN reading "platform
      ;; absorbs fee":
      ;;
      ;;   - The fetch SUCCEEDED but Stripe has not created the charge's balance
      ;;     transaction yet. It is created asynchronously, so at
      ;;     checkout.session.completed time the fee is routinely absent. The
      ;;     charge.updated backfill fills it in moments later and settlement uses
      ;;     the real figure — nothing is absorbed. Informational.
      ;;   - The fetch actually FAILED (non-200, missing credentials). The fee may
      ;;     never be recovered and the platform really does eat it. Still a WARN.
      ;;
      ;; Calling the first case "platform absorbs fee" describes a transient state
      ;; as a final outcome and sends an operator hunting for money that is not
      ;; lost. Observed 2026-08-03 on a live checkout that then settled with the
      ;; correct fee of 74.
      (if (:ok? payment-intent-result)
        (log-fn "INFO" "stripe fee not yet available; awaiting charge.updated backfill"
                {:payment-intent-id (:payment_intent session)
                 :checkout-id checkout-id})
        (log-fn "WARN" "stripe fee fetch failed; platform absorbs fee unless backfilled"
                {:payment-intent-id (:payment_intent session)
                 :reason (or (:reason payment-intent-result) :missing-fee)
                 :status (or (:status payment-intent-result) 200)})))
    (when (and stamped? attribution-ok?)
      (log-fn "INFO" "stripe merchant attribution verified"
              {:checkout-id checkout-id :merchant-account-id (str attributed)}))
    (when-not stamped?
      ;; Legacy pre-gate session: created before merchant-account-id was stamped,
      ;; so there is nothing to verify against. Recorded, not blocked.
      (log-fn "INFO" "stripe legacy unstamped session; merchant attribution unverifiable"
              {:checkout-id checkout-id :session-id (:id session)
               :attributed (str attributed)}))
    (cond
      (or (str/blank? (str checkout-id)) (str/blank? (str buyer))
          (str/blank? (str game-name)))
      (do
        (log-fn "WARN" "stripe webhook missing metadata"
                {:checkout-id checkout-id :buyer buyer :game-name game-name})
        {:status 200 :body {:ignored "missing-metadata"}})

      ;; Quarantine: we asked Stripe to attribute this charge to one merchant and
      ;; it did something else. Do NOT mark paid and do NOT mint ledger funding —
      ;; the money moved, but not on the terms we recorded.
      ;;
      ;; 200, deliberately. A merchant mismatch is permanent; Stripe retries a
      ;; non-2xx for days, so returning 500 would add a retry storm to an incident
      ;; without ever succeeding. The operator reviews and refunds instead.
      (not attribution-ok?)
      (do
        (log-fn "ERROR" "MERCHANT_ATTRIBUTION_MISMATCH - charge quarantined, not marked paid"
                {:checkout-id checkout-id
                 :session-id (:id session)
                 :payment-intent-id (:payment_intent session)
                 :game-name game-name
                 :expected (str stamped)
                 :actual (str attributed)
                 :severity "high"
                 :action "operator review; refund the buyer or correct attribution"})
        {:status 200 :body {:status "quarantined"
                            :error {:code "merchant-attribution-mismatch"
                                    :checkout-id checkout-id
                                    :expected (str stamped)
                                    :actual (str attributed)}}})

      :else
      (let [resp (submit-service-event game-name
                                       "market/mark-checkout-paid"
                                       buyer ["buyer"]
                                       {:checkout-id checkout-id
                                        :payment-ref payment-ref})
            result (gm-result resp)
            error-code (gm-error-code result)]
        (cond
          (or (:success result) (get result "success"))
          (if-not subscription?
            {:status 200 :body {:status "paid"}}
            ;; First cycle of a subscription. Opening it here — rather than from
            ;; invoice.paid — is what makes the billing_reason filter safe: this
            ;; path owns cycle one, that path owns every cycle after. Without
            ;; this, the first renewal would arrive to no subscription and 404.
            (let [sub-resp (submit-service-event
                             game-name "market/record-subscription-started"
                             "game-master" ["game-master"]
                             {:provider-subscription-id (str subscription-id)
                              :buyer buyer
                              :checkout-id checkout-id})
                  sub-result (gm-result sub-resp)]
              (if (or (:success sub-result) (get sub-result "success"))
                {:status 200 :body {:status "paid" :subscriptionId (str subscription-id)}}
                (do
                  ;; The money moved and the checkout is paid; only the
                  ;; subscription record failed. Loud, because renewals will not
                  ;; work until it exists — but 200, since retrying the whole
                  ;; webhook would not fix it and Stripe would hammer for days.
                  (log-fn "ERROR" "SUBSCRIPTION_NOT_RECORDED - paid but renewals will fail"
                          {:checkout-id checkout-id
                           :subscription (str subscription-id)
                           :result sub-result})
                  {:status 200 :body {:status "paid"
                                      :warning "subscription-not-recorded"}}))))

          (or (= "checkout-already-paid" error-code)
              (relayed-conflict? resp result))
          (do
            (log-fn "INFO" "stripe webhook idempotent replay" {:checkout-id checkout-id})
            {:status 200 :body {:status "already-paid"}})

          :else
          (do
            (log-fn "ERROR" "stripe mark-checkout-paid failed" {:body (:body resp)})
            {:status 500 :body {:error {:code "mark-checkout-paid-failed"
                                        :details (:body resp)}}}))))))

(defn- seller-direct-session?
  [session]
  (not (str/blank?
        (some-> (metadata-value (session-metadata session) :commerce-adapter) str))))

(defn- seller-stripe-session-completed-response
  [{:keys [event-id connected-account-id session submit-service-event fallback-game-name
           verify-seller-payment log-fn]}]
  (let [metadata (session-metadata session)
        checkout-id (metadata-value metadata :checkout-id)
        checkout-batch-id (metadata-value metadata :checkout-batch-id)
        buyer (metadata-value metadata :buyer)
        game-name (or (metadata-value metadata :game-name) (fallback-game-name))
        commerce-adapter (some-> (metadata-value metadata :commerce-adapter) str str/lower-case)
        fulfillment-ref (metadata-value metadata :fulfillment-ref)
        subscription-id (or (:subscription session) (get session "subscription"))
        subscription? (not (str/blank? (str subscription-id)))
        market-subscription-id (metadata-value metadata :market-subscription-id)
        plan-sku (metadata-value metadata :plan-sku)
        payment-ref {:platform "stripe"
                     :session-id (:id session)
                     :payment-intent-id (:payment_intent session)
                     :amount {:amount (:amount_total session)
                              :currency (str/upper-case (str (:currency session)))}
                     :stripe-event-id event-id
                     :connected-account connected-account-id}]
    (if (some #(str/blank? (str %))
              [checkout-id checkout-batch-id buyer game-name commerce-adapter
               fulfillment-ref connected-account-id])
      (do
        (log-fn "WARN" "seller checkout webhook missing metadata"
                {:checkout-id checkout-id :checkout-batch-id checkout-batch-id
                 :buyer buyer :game-name game-name :adapter commerce-adapter
                 :fulfillment-ref fulfillment-ref})
        {:status 200 :body {:ignored "missing-seller-checkout-metadata"}})
      (let [resp (submit-service-event game-name
                                       "market/record-seller-checkout-paid"
                                       "game-master" ["game-master"]
                                       {:checkout-id checkout-id
                                        :payment-ref payment-ref})
            result (gm-result resp)
            market-ok? (or (:success result) (get result "success")
                           (= "checkout-already-paid" (gm-error-code result))
                           (relayed-conflict? resp result))]
        (if-not market-ok?
          (do
            (log-fn "ERROR" "seller checkout payment was not recorded"
                    {:checkout-id checkout-id :body (:body resp)})
            {:status 500 :body {:error {:code "record-seller-payment-failed"}}})
          (let [payment-intent-result
                (if subscription?
                  {:ok? true :payment-intent {}}
                  (stripe/fetch-payment-intent
                   {:secret-key (gm-config/resolve-stripe-secret-key)
                    :payment-intent-id (:payment_intent session)
                    :connected-account-id connected-account-id}))
                payment-intent (:payment-intent payment-intent-result)
                verified (when (:ok? payment-intent-result)
                           (verify-seller-payment
                            {:checkout-id checkout-id
                             :checkout-batch-id checkout-batch-id
                             :buyer buyer
                             :commerce-adapter commerce-adapter
                             :plan-sku plan-sku
                             :market-subscription-id market-subscription-id
                             :fulfillment-ref fulfillment-ref
                             :payment-provider "stripe"
                             ;; Stripe includes livemode on every Checkout Session.
                             ;; Forward a processor-neutral mode so fulfillment can
                             ;; fail closed if a test event reaches production.
                             :payment-mode (if (true? (or (:livemode session)
                                                          (get session "livemode")))
                                             "live"
                                             "test")
                             :payment-status (:payment_status session)
                             :stripe-session-id (:id session)
                             :stripe-subscription-id (when subscription? (str subscription-id))
                             :stripe-invoice-id (some-> (or (:invoice session)
                                                            (get session "invoice")) str)
                             :stripe-payment-intent-id (:payment_intent session)
                             :stripe-event-id event-id
                             :connected-account-id connected-account-id
                             :application-fee-amount (:application_fee_amount payment-intent)
                             :amount-total (:amount_total session)
                             :currency (:currency session)}))]
            (if (and (:ok? payment-intent-result) (:ok? verified))
              (if-not subscription?
                {:status 200 :body {:status "paid" :paymentGroup checkout-id}}
                (let [sub-resp (submit-service-event
                                game-name "market/record-subscription-started"
                                "game-master" ["game-master"]
                                {:provider-subscription-id (str subscription-id)
                                 :market-subscription-id market-subscription-id
                                 :provider "stripe"
                                 :buyer buyer
                                 :checkout-id checkout-id})
                      sub-result (gm-result sub-resp)]
                  (if (or (:success sub-result) (get sub-result "success"))
                    {:status 200 :body {:status "paid" :paymentGroup checkout-id
                                        :subscriptionId (or (get-in sub-result [:data :subscription-id])
                                                            (get-in sub-result ["data" "subscription-id"]))}}
                    {:status 500 :body {:error {:code "subscription-record-failed"
                                                :details sub-result}}})))
              (do
                (log-fn "ERROR" "seller paid checkout verification failed"
                        {:checkout-id checkout-id
                         :adapter commerce-adapter
                         :details (or (:error verified) (:error payment-intent-result))})
                ;; Return 5xx so Stripe retries. Market recording and seller
                ;; fulfillment are both idempotent, so either side may commit first.
                {:status 500
                 :body {:error {:code "seller-payment-verification-failed"}}}))))))))

(defn stripe-session-completed-response
  [opts]
  (if (seller-direct-session? (:session opts))
    (seller-stripe-session-completed-response opts)
    (native-stripe-session-completed-response opts)))

(defn stripe-charge-backfill-response
  [{:keys [event-id charge submit-service-event fallback-game-name log-fn]}]
  (let [payment-intent-id (:payment_intent charge)
        payment-intent-result (stripe/fetch-payment-intent
                               {:secret-key (gm-config/resolve-stripe-secret-key)
                                :payment-intent-id payment-intent-id})
        payment-intent (:payment-intent payment-intent-result)
        metadata (session-metadata payment-intent)
        checkout-id (metadata-value metadata :checkout-id)
        buyer (metadata-value metadata :buyer)
        game-name (or (metadata-value metadata :game-name) (fallback-game-name))
        fee (stripe/payment-intent-fee payment-intent)
        available-on (stripe/payment-intent-available-on payment-intent)
        payment-ref (cond-> {:platform "stripe"
                             :payment-intent-id payment-intent-id}
                      fee (assoc :fee fee)
                      available-on (assoc :available-on available-on))]
    (if (or (str/blank? (str checkout-id)) (str/blank? (str buyer))
            (str/blank? (str game-name)))
      (do
        (log-fn "WARN" "stripe charge webhook missing metadata"
                {:checkout-id checkout-id :buyer buyer :game-name game-name
                 :payment-intent-id payment-intent-id})
        {:status 200 :body {:ignored "missing-metadata"}})
      (let [resp (submit-service-event game-name
                                       "market/record-checkout-payment-ref"
                                       buyer ["buyer"]
                                       {:checkout-id checkout-id
                                        :payment-ref payment-ref})
            result (gm-result resp)
            error-code (gm-error-code result)]
        (cond
          (or (:success result) (get result "success"))
          {:status 200 :body {:status (if fee "fee-recorded" "pending-fee")}}

          (= "checkout-not-found" error-code)
          (do
            (log-fn "WARN" "stripe fee backfill missed checkout"
                    {:checkout-id checkout-id :payment-intent-id payment-intent-id})
            {:status 200 :body {:ignored "checkout-not-found"}})

          :else
          (do
            (log-fn "ERROR" "stripe fee backfill failed" {:body (:body resp)})
            {:status 500 :body {:error {:code "record-checkout-payment-ref-failed"
                                        :details (:body resp)}}}))))))

(defn stripe-account-updated-response
  "Handles account.updated for a PLAYER account: stamps (owner-id, game-name) in
   metadata and upserts the (owner, game) payout record.

   House (treasury) accounts are NOT handled here. They stamp metadata[account]
   (e.g. @intergraph) and are owned end-to-end by MoM's own
   `treasury-stripe-webhook-handler` at /webhooks/stripe/treasury, which is treasury-
   authoritative. The GM holds no treasury credential, so upserting one from here
   returned 403 — and, being a 5xx, invited Stripe to retry it for days. Ignored with
   a 200 instead."
  [{:keys [account upsert-connect-account update-connect-account-by-external-id
           event-created log-fn]}]
  (let [metadata (session-metadata account)
        house-account (metadata-value metadata :account)
        owner-id (metadata-value metadata :owner-id)
        game-name (metadata-value metadata :game-name)
        payouts-enabled? (boolean (or (:payouts_enabled account) (get account "payouts_enabled")))
        account-id (or (:id account) (get account "id"))
        ok-resp {:status 200 :body {:status (if payouts-enabled? "enabled" "onboarding")}}]
    (cond
      ;; House account: MoM handles these natively. ACK so Stripe stops retrying.
      (not (str/blank? (str house-account)))
      {:status 200 :body {:ignored "house-account-handled-by-mom" :account house-account}}

      (and (or (str/blank? (str owner-id)) (str/blank? (str game-name)))
           (nil? update-connect-account-by-external-id))
      (do (log-fn "WARN" "stripe account.updated missing metadata"
                  {:account-id account-id :owner-id owner-id :game-name game-name})
          {:status 200 :body {:ignored "missing-metadata"}})

      (or (str/blank? (str owner-id)) (str/blank? (str game-name)))
      (let [resp (update-connect-account-by-external-id
                  {:external-id account-id
                   :status (if payouts-enabled? "enabled" "onboarding")
                   :payouts-enabled payouts-enabled?
                   :charges-enabled (boolean (or (:charges_enabled account)
                                                 (get account "charges_enabled")))
                   :card-payments-enabled (= "active" (str (or (get-in account [:capabilities :card_payments])
                                                                (get-in account ["capabilities" "card_payments"]))))
                   :last-provider-event-at event-created})]
        (if (<= 200 (:status resp) 299)
          ok-resp
          (do (log-fn "ERROR" "stripe Standard account update failed" {:body (:body resp)})
              {:status 500 :body {:error {:code "connect-update-failed"}}})))

      :else
      (let [resp (upsert-connect-account game-name owner-id
                                         {:external-id account-id
                                          :payouts-enabled payouts-enabled?})]
        (if (<= 200 (:status resp) 299)
          ok-resp
          (do (log-fn "ERROR" "stripe account.updated upsert failed" {:body (:body resp)})
              {:status 500 :body {:error {:code "connect-upsert-failed"}}}))))))

(defn stripe-account-deauthorized-response
  [{:keys [account-id update-connect-account-by-external-id event-created log-fn]}]
  (if (str/blank? (str account-id))
    {:status 200 :body {:ignored "missing-connected-account"}}
    (let [resp (update-connect-account-by-external-id
                {:external-id account-id :status "disabled"
                 :payouts-enabled false :charges-enabled false
                 :card-payments-enabled false :last-provider-event-at event-created})]
      (if (<= 200 (:status resp) 299)
        {:status 200 :body {:status "disabled"}}
        (do (log-fn "ERROR" "stripe account deauthorization update failed" {:body (:body resp)})
            {:status 500 :body {:error {:code "connect-update-failed"}}})))))

(defn stripe-transfer-reversed-response
  "Handles transfer.reversed/transfer.failed: reads the withdrawal-id + player-name
   stamped in the transfer metadata and asks the GM to reverse the pending ledger
   reservation back to the player (idempotent via the withdrawal-id)."
  [{:keys [event-type transfer submit-service-event log-fn]}]
  (let [metadata (session-metadata transfer)
        withdrawal-id (metadata-value metadata :withdrawal-id)
        game-name (metadata-value metadata :game-name)
        player-name (metadata-value metadata :player-name)]
    (if (or (str/blank? (str withdrawal-id)) (str/blank? (str game-name))
            (str/blank? (str player-name)))
      (do (log-fn "WARN" "stripe transfer webhook missing metadata"
                  {:type event-type :withdrawal-id withdrawal-id :game-name game-name})
          {:status 200 :body {:ignored "missing-metadata"}})
      (do (submit-service-event game-name "withdrawal/reverse" player-name ["buyer"]
                                {:withdrawal-id withdrawal-id :reason (str event-type)})
          {:status 200 :body {:status "reversed" :withdrawalId withdrawal-id}}))))

;; --- Refunds & disputes ---
;;
;; Two very different inbound cases share one webhook:
;;
;;  * a refund WE issued — it carries metadata[refund-id], and the webhook is
;;    the safety net for a crash between the Stripe call and mark-refund-settled;
;;  * a refund someone issued in the Stripe dashboard — no refund-id, so the
;;    checkout is resolved off the PaymentIntent metadata and reconciled as an
;;    externally-initiated refund.

(defn- refund-amount
  [refund]
  (let [a (or (:amount refund) (get refund "amount"))]
    (when (number? a) (long a))))

(defn- checkout-from-payment-intent
  "Resolve routing metadata from a charge's PaymentIntent
   metadata (stamped at checkout-session creation). Subscription renewals are
   recorded after their invoice is paid, so resolve those by invoice reference."
  ([payment-intent-id fallback-game-name]
   (checkout-from-payment-intent payment-intent-id fallback-game-name nil nil))
  ([payment-intent-id fallback-game-name connected-account-id]
   (checkout-from-payment-intent payment-intent-id fallback-game-name
                                 connected-account-id nil))
  ([payment-intent-id fallback-game-name connected-account-id submit-service-event]
   (let [result (stripe/fetch-payment-intent
                 {:secret-key (gm-config/resolve-stripe-secret-key)
                  :payment-intent-id payment-intent-id
                  :connected-account-id connected-account-id})
         payment-intent (:payment-intent result)
         metadata (session-metadata payment-intent)
         invoice-id-on-intent (or (:invoice payment-intent)
                                  (get payment-intent "invoice"))
         invoice-lookup (when (and submit-service-event connected-account-id
                                   (not invoice-id-on-intent)
                                   (let [checkout-id (metadata-value metadata :checkout-id)]
                                     (or (nil? checkout-id)
                                         (str/blank? (str checkout-id)))))
                          (stripe/fetch-invoice-for-payment-intent
                           {:secret-key (gm-config/resolve-stripe-secret-key)
                            :payment-intent-id payment-intent-id
                            :connected-account-id connected-account-id}))
         invoice (:invoice invoice-lookup)
         invoice-metadata (or (get-in invoice [:parent :subscription_details :metadata])
                              (get-in invoice ["parent" "subscription_details" "metadata"])
                              (:metadata invoice) (get invoice "metadata"))
         game-name (or (metadata-value metadata :game-name)
                       (metadata-value invoice-metadata :game-name)
                       (fallback-game-name))
         invoice-id (or invoice-id-on-intent (:id invoice) (get invoice "id"))
         checkout-result (when (and submit-service-event invoice-id
                                    (str/blank? (str (metadata-value metadata :checkout-id))))
                           (submit-service-event game-name "market/query-checkouts"
                                                 "game-master" ["game-master"]
                                                 {:filters {:invoice-id (str invoice-id)}}))
         checkout (first (or (get-in (gm-result checkout-result) [:data :checkouts])
                             (get-in (gm-result checkout-result) ["data" "checkouts"])
                             []))]
     {:checkout-id (or (metadata-value metadata :checkout-id)
                       (:id checkout) (get checkout "id")
                       (metadata-value invoice-metadata :checkout-id))
      :buyer (or (metadata-value metadata :buyer)
                 (:buyer checkout) (get checkout "buyer")
                 (metadata-value invoice-metadata :buyer))
      :commerce-adapter (or (metadata-value metadata :commerce-adapter)
                            (:commerce-adapter checkout)
                            (get checkout "commerce-adapter")
                            (metadata-value invoice-metadata :commerce-adapter))
      :fulfillment-ref (or (metadata-value metadata :fulfillment-ref)
                           (:fulfillment-ref checkout)
                           (get checkout "fulfillment-ref")
                           (metadata-value invoice-metadata :fulfillment-ref))
      :game-name game-name})))

(defn- reconcile-application-fee!
  "Market decides whether a seller-direct fee is returned. Stripe only performs
   that decision; Market then removes the returned fee from commission escrow."
  [{:keys [game-name payment-intent-id connected-account-id refund-record
           submit-service-event log-fn]}]
  (let [field (fn [k] (or (get refund-record k)
                          (get refund-record (name k))))
        status (some-> (field :fee-adjustment-status) str
                       (str/replace #"^:" ""))
        amount (field :fee-adjustment-amount)
        refund-id (field :refund-id)
        order-id (field :order-id)]
    (if (not= "pending" status)
      {:ok? true}
      (let [fee (if (pos? (or amount 0))
                  (stripe/refund-application-fee
                   {:secret-key (gm-config/resolve-stripe-secret-key)
                    :payment-intent-id payment-intent-id
                    :connected-account-id connected-account-id
                    :amount amount :refund-id refund-id})
                  {:ok? true :id "no-fee"})
            recorded (when (:ok? fee)
                       (submit-service-event
                        game-name "market/mark-application-fee-refunded"
                        "game-master" ["game-master"]
                        {:order-id order-id :refund-id refund-id
                         :provider-fee-refund-id (:id fee)}))]
        (if (and (:ok? fee)
                 (or (:success (gm-result recorded))
                     (get (gm-result recorded) "success"))
                 (every? #(= "succeeded"
                             (some-> (or (:status %) (get % "status"))
                                     str (str/replace #"^:" "")))
                         (or (get-in recorded [:body :effect-results])
                             (get-in recorded [:body "effect-results"])
                             [])))
          {:ok? true}
          (do
            (log-fn "ERROR" "seller application fee reconciliation pending"
                    {:refund-id refund-id :fee-error (:error-code fee)
                     :market-response (:body recorded)})
            {:ok? false}))))))

(defn stripe-subscription-invoice-paid-response
  "Handles invoice.paid for a renewal or immediate prorated plan change.

   `subscription_cycle` and `subscription_update` are routed here. The first cycle
   also fires invoice.paid — with billing_reason subscription_create — and it is
   already recorded by checkout.session.completed. Handling both would mint two
   paid orders for one payment and pay commission twice, so this filter is the
   only thing standing between a subscriber and being double-billed on day one.
   It is asserted in the tests for that reason.

   Routing metadata comes off the SUBSCRIPTION, because an invoice arrives with
   neither a session nor the original PaymentIntent."
  [{:keys [event-id invoice connected-account-id submit-service-event fallback-game-name
           verify-seller-payment log-fn]}]
  (let [billing-reason (or (:billing_reason invoice) (get invoice "billing_reason"))
        subscription-id (or (:subscription invoice) (get invoice "subscription")
                            (get-in invoice [:parent :subscription_details :subscription])
                            (get-in invoice ["parent" "subscription_details" "subscription"]))
        invoice-id (or (:id invoice) (get invoice "id"))
        metadata (session-metadata (or (:subscription_details invoice)
                                       (get invoice "subscription_details")
                                       (get-in invoice [:parent :subscription_details])
                                       (get-in invoice ["parent" "subscription_details"])
                                       invoice))
        commerce-adapter (some-> (metadata-value metadata :commerce-adapter) str str/lower-case)
        checkout-batch-id (metadata-value metadata :checkout-batch-id)
        buyer (metadata-value metadata :buyer)
        fulfillment-ref (metadata-value metadata :fulfillment-ref)
        metadata-plan-sku (metadata-value metadata :plan-sku)
        invoice-lines (or (get-in invoice [:lines :data])
                          (get-in invoice ["lines" "data"]) [])
        active-line (or (first (filter #(pos? (long (or (:amount %) (get % "amount") 0)))
                                       invoice-lines))
                        (first invoice-lines))
        price-ref (or (get-in active-line [:price :id])
                      (get-in active-line ["price" "id"])
                      (get-in active-line [:pricing :price_details :price])
                      (get-in active-line ["pricing" "price_details" "price"]))
        resolved-sku (when (and commerce-adapter price-ref)
                       (seller-commerce/plan-sku-for-price! commerce-adapter price-ref))
        plan-sku (or resolved-sku metadata-plan-sku)
        game-name (or (metadata-value metadata :game-name) (fallback-game-name))
        charge-id (or (:charge invoice) (get invoice "charge"))]
    (cond
      (not (contains? #{"subscription_cycle" "subscription_update"}
                      (str billing-reason)))
      (do
        (log-fn "INFO" "stripe invoice ignored (not a renewal cycle)"
                {:billing-reason billing-reason :invoice invoice-id})
        {:status 200 :body {:status "ignored" :reason "not-a-paid-cycle"}})

      (str/blank? (str subscription-id))
      (do
        (log-fn "WARN" "stripe invoice has no subscription" {:invoice invoice-id})
        {:status 200 :body {:status "ignored" :reason "no-subscription"}})

      (and (= "subscription_update" (str billing-reason))
           (not (str/blank? (str commerce-adapter)))
           (str/blank? (str resolved-sku)))
      (do
        (log-fn "ERROR" "seller plan change could not resolve catalog price"
                {:invoice invoice-id :adapter commerce-adapter :price-ref price-ref})
        {:status 503 :body {:error {:code "seller-plan-unresolved"}}})

      :else
      (let [application-fee (or (:application_fee_amount invoice)
                                (get invoice "application_fee_amount"))
            payment-ref (cond-> (payment-ref-with-gross
                                 (cond-> {:provider "stripe"
                                          :invoice-id (str invoice-id)}
                                   charge-id (assoc :charge-id (str charge-id)))
                                 invoice :amount_paid)
                          (integer? application-fee)
                          (assoc :application-fee
                                 {:amount application-fee
                                  :currency (str/upper-case
                                             (str (or (:currency invoice)
                                                      (get invoice "currency"))))}))
            resp (submit-service-event
                   game-name "market/record-subscription-invoice-paid"
                   "game-master" ["game-master"]
                   (cond-> {:provider-subscription-id (str subscription-id)
                            :invoice-id (str invoice-id)
                            :billing-reason (str billing-reason)
                            :current-period-end (or (:period_end invoice) (get invoice "period_end"))
                            :payment-ref payment-ref}
                     (and resolved-sku (not (str/blank? (str resolved-sku))))
                     (assoc :plan-sku plan-sku)))
            result (gm-result resp)
            seller-direct? (not (str/blank? (str commerce-adapter)))
            cycle-checkout-id (or (get-in result [:data :checkout-id])
                                  (get-in result ["data" "checkout-id"]))
            cycle-order-id (or (get-in result [:data :order-id])
                               (get-in result ["data" "order-id"]))
            verified (when (and seller-direct?
                                (or (:success result) (get result "success")))
                       (if (or (str/blank? (str cycle-checkout-id))
                               (str/blank? (str cycle-order-id)))
                         {:ok? false
                          :error {:code "seller-subscription-cycle-reference-missing"}}
                         (verify-seller-payment
                          {:checkout-id cycle-checkout-id
                           :checkout-batch-id checkout-batch-id
                           :order-ids [cycle-order-id]
                           :buyer buyer
                           :commerce-adapter commerce-adapter
                           :plan-sku plan-sku
                           :fulfillment-ref fulfillment-ref
                           :market-subscription-id (or (get-in result [:data :subscription-id])
                                                       (get-in result ["data" "subscription-id"]))
                           :payment-provider "stripe"
                           :stripe-subscription-id (str subscription-id)
                           :stripe-invoice-id (str invoice-id)
                           :stripe-event-id event-id
                           :connected-account-id connected-account-id
                           :application-fee-amount application-fee
                           :amount-total (or (:amount_paid invoice) (get invoice "amount_paid"))
                           :currency (or (:currency invoice) (get invoice "currency"))
                           :billing-reason (str billing-reason)})))]
        (if (or (:success result) (get result "success"))
          (if (or (not seller-direct?) (:ok? verified))
            {:status 200 :body {:status "paid" :subscriptionId (str subscription-id)}}
            {:status 500 :body {:error {:code "seller-subscription-confirmation-failed"
                                        :details (:error verified)}}})
          (do
            (log-fn "WARN" "subscription invoice rejected"
                    {:subscription subscription-id :invoice invoice-id :result result})
            ;; A catalog sync can lag a paid plan change. Retry that invoice
            ;; rather than acknowledging a charge whose entitlement and order
            ;; have not been recorded.
            (if (or (= "subscription-plan-unresolved" (gm-error-code result))
                    (>= (long (or (:status resp) 0)) 500))
              {:status 503 :body {:error {:code "subscription-invoice-retry-required"}}}
              {:status 200 :body {:status "rejected"
                                  :subscriptionId (str subscription-id)}})))))))

(defn stripe-subscription-updated-response
  [{:keys [event-id subscription connected-account-id submit-service-event fallback-game-name
           verify-seller-payment log-fn]}]
  (let [subscription-id (or (:id subscription) (get subscription "id"))
        metadata (session-metadata subscription)
        game-name (or (metadata-value metadata :game-name) (fallback-game-name))
        stripe-status (str (or (:status subscription) (get subscription "status")))
        status (case stripe-status
                 "active" "active"
                 "trialing" "active"
                 "past_due" "past-due"
                 "unpaid" "past-due"
                 "paused" "paused"
                 "canceled" "canceled"
                 "pending-activation")
        adapter (some-> (metadata-value metadata :commerce-adapter) str str/lower-case)
        resp (submit-service-event
              game-name "market/update-subscription-status"
              "game-master" ["game-master"]
              {:provider-subscription-id (str subscription-id)
               :status status
               :current-period-end (or (:current_period_end subscription)
                                       (get subscription "current_period_end"))
               :cancel-at-period-end (boolean (or (:cancel_at_period_end subscription)
                                                  (get subscription "cancel_at_period_end")))})
        market-ok? (or (:success (gm-result resp))
                       (get (gm-result resp) "success"))
        verified (when (and market-ok? (not (str/blank? (str adapter))))
                   (verify-seller-payment
                    {:event-kind "subscription-status"
                     :commerce-adapter adapter
                     :market-subscription-id (or (get-in (gm-result resp) [:data :subscription-id])
                                                 (get-in (gm-result resp) ["data" "subscription-id"]))
                     :stripe-subscription-id (str subscription-id)
                     :stripe-event-id event-id
                     :connected-account-id connected-account-id
                     :status status
                     :cancel-at-period-end (boolean
                                            (or (:cancel_at_period_end subscription)
                                                (get subscription "cancel_at_period_end")))}))]
    (log-fn "INFO" "stripe subscription status updated"
            {:subscription subscription-id :status status})
    (if (and market-ok? verified (not (:ok? verified)))
      {:status 503 :body {:error {:code "seller-subscription-status-delivery-failed"}}}
      {:status 200 :body {:status (if market-ok? "updated" "rejected")}})))

(defn stripe-subscription-deleted-response
  "Handles customer.subscription.deleted — a cancellation, whether initiated by
   the buyer, the seller, or Stripe itself after failed payments. Cancels on our
   side so no further cycle can mint an order."
  [{:keys [event-id subscription connected-account-id submit-service-event fallback-game-name
           verify-seller-payment log-fn]}]
  (let [subscription-id (or (:id subscription) (get subscription "id"))
        metadata (session-metadata subscription)
        adapter (some-> (metadata-value metadata :commerce-adapter) str str/lower-case)
        game-name (or (metadata-value metadata :game-name) (fallback-game-name))
        resp (submit-service-event game-name "market/cancel-subscription"
                                   "game-master" ["game-master"]
                                   {:provider-subscription-id (str subscription-id)})
        result (gm-result resp)]
    (log-fn "INFO" "stripe subscription deleted" {:subscription subscription-id})
    (if (or (:success result) (get result "success"))
      (let [verified (when (not (str/blank? (str adapter)))
                       (verify-seller-payment {:event-kind "subscription-status"
                                               :commerce-adapter adapter
                                               :market-subscription-id (or (get-in result [:data :subscription-id])
                                                                           (get-in result ["data" "subscription-id"]))
                                               :stripe-subscription-id (str subscription-id)
                                               :stripe-event-id event-id
                                               :connected-account-id connected-account-id
                                               :status "canceled"}))]
        (if (and verified (not (:ok? verified)))
          {:status 503 :body {:error {:code "seller-subscription-cancel-delivery-failed"}}}
          {:status 200 :body {:status "canceled" :subscriptionId (str subscription-id)}}))
      {:status 200 :body {:status "rejected" :subscriptionId (str subscription-id)}})))

(defn stripe-refund-response
  "Handles charge.refunded / refund.updated. A refund carrying our
   metadata[refund-id] settles that refund (idempotently — redelivery is
   expected); anything else is reconciled as an externally-initiated refund
   against the checkout."
  [{:keys [event-id event-type event-created charge refund connected-account-id verify-seller-payment
           submit-service-event fallback-game-name log-fn]}]
  (if (and (= event-type "charge.refunded") charge (nil? refund)
           (empty? (or (get-in charge [:refunds :data])
                       (get-in charge ["refunds" "data"]))))
    (let [listed (stripe/fetch-charge-refunds
                  {:secret-key (gm-config/resolve-stripe-secret-key)
                   :charge-id (object-value charge :id)
                   :connected-account-id connected-account-id})
          refunds (:refunds listed)]
      (if (and (:ok? listed) (seq refunds))
        (let [responses (mapv (fn [item]
                                (stripe-refund-response
                                 {:event-id (str event-id ":" (object-value item :id))
                                  :event-type event-type :event-created event-created
                                  :charge charge :refund item
                                  :connected-account-id connected-account-id
                                  :verify-seller-payment verify-seller-payment
                                  :submit-service-event submit-service-event
                                  :fallback-game-name fallback-game-name :log-fn log-fn}))
                              refunds)]
          (if (every? #(= 200 (:status %)) responses)
            {:status 200 :body {:status "charge-refunds-reconciled"
                                :count (count responses)}}
            (first (remove #(= 200 (:status %)) responses))))
        {:status 503 :body {:error {:code "charge-refunds-unavailable"}}}))
  (let [refund-obj (or refund
                       (first (or (get-in charge [:refunds :data])
                                  (get-in charge ["refunds" "data"])
                                  [])))
        metadata (session-metadata refund-obj)
        refund-id (metadata-value metadata :refund-id)
        order-id (metadata-value metadata :order-id)
        refund-status (or (:status refund-obj) (get refund-obj "status")
                          (when charge "succeeded"))
        stripe-refund-id (or (:id refund-obj) (get refund-obj "id"))
        payment-intent-id (or (:payment_intent charge) (get charge "payment_intent")
                              (:payment_intent refund-obj) (get refund-obj "payment_intent"))
        amount (or (refund-amount refund-obj)
                   (let [a (or (:amount_refunded charge) (get charge "amount_refunded"))]
                     (when (number? a) (long a))))
        payment-context (when-not (str/blank? (str payment-intent-id))
                          (checkout-from-payment-intent payment-intent-id
                                                        fallback-game-name
                                                        connected-account-id
                                                        submit-service-event))
        commerce-adapter (:commerce-adapter payment-context)
        deliver (fn []
                  (if (and verify-seller-payment
                           (not (str/blank? (str commerce-adapter))))
                    (verify-seller-payment
                     {:event-kind "refund"
                      :commerce-adapter commerce-adapter
                      :checkout-id (:checkout-id payment-context)
                      :fulfillment-ref (:fulfillment-ref payment-context)
                      :stripe-payment-intent-id (str payment-intent-id)
                      :stripe-refund-id (str stripe-refund-id)
                      :stripe-event-id event-id
                      :occurred-at (when-let [created (object-value refund-obj :created)]
                                     (.toString (java.time.Instant/ofEpochSecond
                                                 (long created))))
                      :connected-account-id connected-account-id
                      :amount amount})
                    {:ok? true}))]
    (cond
      (contains? #{"pending" "requires_action"} (str refund-status))
      {:status 200 :body {:status "refund-pending"}}

      (contains? #{"failed" "canceled"} (str refund-status))
      (if (and refund-id order-id)
        (let [reversed (submit-service-event
                        (:game-name payment-context) "market/reverse-refund"
                        "game-master" ["game-master"]
                        {:order-id order-id :refund-id refund-id
                         :reason (str refund-status)})]
          (if (or (:success (gm-result reversed))
                  (get (gm-result reversed) "success"))
            {:status 200 :body {:status "refund-failed"}}
            {:status 503 :body {:error {:code "refund-reversal-failed"}}}))
        {:status 200 :body {:status "external-refund-failed"}})

      ;; Our own refund: close the loop. mark-refund-settled is idempotent, so a
      ;; redelivered webhook after the orchestration already settled is a no-op.
      (not (str/blank? (str refund-id)))
      (let [game-name (:game-name payment-context)
            resp (submit-service-event game-name "market/mark-refund-settled"
                                       "game-master" ["game-master"]
                                       (cond-> {:refund-id refund-id
                                                :order-id order-id}
                                         stripe-refund-id (assoc :stripe-refund-id stripe-refund-id)))
            result (gm-result resp)
            error-code (gm-error-code result)]
        (cond
          (or (:success result) (get result "success"))
          (let [fee-result (reconcile-application-fee!
                            {:game-name game-name :payment-intent-id payment-intent-id
                             :connected-account-id connected-account-id
                             :refund-record (or (:data result) (get result "data"))
                             :submit-service-event submit-service-event
                             :log-fn log-fn})
                seller-result (deliver)]
            (if (and (:ok? fee-result) (:ok? seller-result))
              {:status 200 :body {:status "settled" :refundId refund-id}}
              {:status 503 :body {:error {:code "refund-reconciliation-pending"}}}))

          (= "refund-not-reserved" error-code)
          (do (log-fn "INFO" "stripe refund webhook idempotent replay" {:refund-id refund-id})
              (if (:ok? (deliver))
                {:status 200 :body {:status "already-settled" :refundId refund-id}}
                {:status 503 :body {:error {:code "seller-refund-delivery-failed"}}}))

          :else
          (do (log-fn "ERROR" "stripe mark-refund-settled failed" {:body (:body resp)})
              {:status 500 :body {:error {:code "mark-refund-settled-failed"
                                          :details (:body resp)}}})))

      (str/blank? (str payment-intent-id))
      (do (log-fn "WARN" "stripe refund webhook missing metadata" {:type event-type})
          {:status 200 :body {:ignored "missing-metadata"}})

      :else
      ;; Dashboard-initiated (or otherwise external) refund: reconcile it so the
      ;; shadow ledger does not silently drift away from Stripe.
      (let [{:keys [checkout-id game-name]} payment-context]
        (if (or (str/blank? (str checkout-id)) (nil? amount) (not (pos? amount)))
          (do (log-fn "WARN" "stripe external refund unresolvable"
                      {:payment-intent-id payment-intent-id :checkout-id checkout-id
                       :amount amount})
              {:status 200 :body {:ignored "missing-metadata"}})
          (let [outcome (external-refund-record!
                         submit-service-event game-name
                         (cond-> {:checkout-id checkout-id
                                  :stripe-refund-id (str stripe-refund-id)
                                  :amount amount
                                  :reason "external-refund"}
                           (integer? event-created)
                           (assoc :occurred-at (* 1000 event-created))))]
            (if (:ok? outcome)
              (let [fees (mapv (fn [record]
                                 (reconcile-application-fee!
                                  {:game-name game-name
                                   :payment-intent-id payment-intent-id
                                   :connected-account-id connected-account-id
                                   :refund-record record
                                   :submit-service-event submit-service-event
                                   :log-fn log-fn}))
                               (:refunds outcome))
                    seller-result (deliver)]
                (if (and (every? :ok? fees) (:ok? seller-result))
                  {:status 200 :body {:status "external-refund-recorded"
                                      :checkoutId checkout-id}}
                  {:status 503 :body {:error {:code "refund-reconciliation-pending"}}}))
              (do (log-fn "ERROR" "stripe external refund reconcile failed" {:outcome outcome})
                  {:status 500 :body {:error {:code "record-external-refund-failed"
                                              :details (:body (:recorded outcome))}}})))))))))

(defn stripe-dispute-response
  "Handles charge.dispute.created / charge.dispute.closed. Opening a dispute
   freezes the affected orders and records it — no ledger movement, because the
   money has not actually left and comes back if we win. Closing it resolves:
   `won` unfreezes, `lost` additionally routes the amount through the
   externally-initiated refund path, since the funds really did leave."
  [{:keys [event-id event-type dispute connected-account-id verify-seller-payment
           submit-service-event fallback-game-name log-fn]}]
  (let [dispute-id (or (:id dispute) (get dispute "id"))
        payment-intent-id (or (:payment_intent dispute) (get dispute "payment_intent"))
        amount (let [a (or (:amount dispute) (get dispute "amount"))]
                 (when (number? a) (long a)))
        status (str (or (:status dispute) (get dispute "status")))
        reason (str (or (:reason dispute) (get dispute "reason")))
        closed? (= event-type "charge.dispute.closed")
        outcome (cond
                  (contains? #{"won" "warning_closed"} status) "won"
                  (contains? #{"lost" "charge_refunded"} status) "lost")]
    (if (str/blank? (str payment-intent-id))
      (do (log-fn "WARN" "stripe dispute webhook missing payment intent"
                  {:type event-type :dispute-id dispute-id})
          {:status 200 :body {:ignored "missing-metadata"}})
      (let [{:keys [checkout-id game-name commerce-adapter fulfillment-ref]}
            (checkout-from-payment-intent payment-intent-id fallback-game-name
                                          connected-account-id submit-service-event)
            deliver (fn []
                      (if (and verify-seller-payment
                               (not (str/blank? (str commerce-adapter))))
                        (verify-seller-payment
                         {:event-kind "chargeback"
                          :commerce-adapter commerce-adapter
                          :checkout-id checkout-id :fulfillment-ref fulfillment-ref
                          :stripe-payment-intent-id (str payment-intent-id)
                          :stripe-dispute-id (str dispute-id)
                          :stripe-event-id event-id
                          :connected-account-id connected-account-id
                          :status status :outcome outcome :amount amount})
                        {:ok? true}))]
        (cond
          (str/blank? (str checkout-id))
          (do (log-fn "WARN" "stripe dispute webhook unresolvable checkout"
                      {:dispute-id dispute-id :payment-intent-id payment-intent-id})
              {:status 200 :body {:ignored "missing-metadata"}})

          (not closed?)
          (do
            ;; Loud on purpose: a chargeback is money at risk and freezes payouts.
            (log-fn "ERROR" "stripe dispute opened"
                    {:dispute-id dispute-id :checkout-id checkout-id
                     :amount amount :reason reason})
            (let [recorded (submit-service-event game-name "market/record-chargeback"
                                                 "game-master" ["game-master"]
                                                 (cond-> {:checkout-id checkout-id
                                                          :stripe-dispute-id (str dispute-id)
                                                          :reason reason}
                                                   amount (assoc :amount amount)))]
              (if-not (or (:success (gm-result recorded))
                          (get (gm-result recorded) "success"))
                {:status 503 :body {:error {:code "market-chargeback-record-failed"}}}
                (if (:ok? (deliver))
                  {:status 200 :body {:status "dispute-recorded" :disputeId dispute-id}}
                  {:status 503 :body {:error {:code "seller-chargeback-delivery-failed"}}}))))

          (nil? outcome)
          (do (log-fn "INFO" "stripe dispute closed with no decisive outcome"
                      {:dispute-id dispute-id :status status})
              {:status 200 :body {:ignored "dispute-inconclusive"}})

          :else
          (let [resp (submit-service-event game-name "market/resolve-chargeback"
                                           "game-master" ["game-master"]
                                           {:checkout-id checkout-id
                                            :stripe-dispute-id (str dispute-id)
                                            :outcome outcome})
                result (gm-result resp)
                data (or (:data result) (get result "data"))
                needs-refund? (true? (or (:requires-refund data) (get data "requires-refund")))]
            (if-not (or (:success result) (get result "success"))
              {:status 503 :body {:error {:code "chargeback-resolution-failed"}}}
              (let [reconciled (if (and needs-refund? amount (pos? amount))
                                 (external-refund-record!
                                  submit-service-event game-name
                                  {:checkout-id checkout-id
                                   :stripe-refund-id (str "dispute:" dispute-id)
                                   :amount amount
                                   :reason "chargeback-lost"})
                                 {:ok? true})]
                (if (and (:ok? reconciled) (:ok? (deliver)))
                  {:status 200 :body {:status (str "dispute-" outcome)
                                      :disputeId dispute-id}}
                  {:status 503 :body {:error {:code "chargeback-resolution-delivery-failed"}}})))))))))

(defn handle-stripe-webhook
  [{:keys [raw-body signature webhook-secrets expected-game-name submit-service-event fallback-game-name
           verify-seller-payment upsert-connect-account
           update-connect-account-by-external-id log-fn]}]
  (let [secrets (or (seq webhook-secrets) [(gm-config/resolve-stripe-webhook-secret)])
        checks (map #(stripe/verify-signature raw-body signature %) secrets)
        verify (or (some #(when (:ok? %) %) checks) (first checks))]
    (cond
      (not (:ok? verify))
      (do
        (log-fn "WARN" "stripe webhook signature rejected" {:reason (:reason verify)})
        {:status 400 :body {:error {:code "invalid-signature" :reason (:reason verify)}}})

      :else
      (let [event (stripe/parse-event raw-body)
            event-type (:type event)
            event-id (:id event)
            event-created (:created event)
            connected-account-id (:account event)
            object (get-in event [:data :object])
            verify-seller-payment (if (and verify-seller-payment
                                           (integer? event-created))
                                    (fn [payment]
                                      (verify-seller-payment
                                       (update payment :occurred-at
                                               #(or % (str (java.time.Instant/ofEpochSecond
                                                            (long event-created)))))))
                                    verify-seller-payment)]
        (log-fn "INFO" "stripe webhook received" {:type event-type :id event-id})
        (cond
          (and expected-game-name
               (let [game (metadata-value (:metadata object) :game-name)]
                 (or (and game (not= game expected-game-name))
                     (and (= event-type "checkout.session.completed")
                          (not= game expected-game-name)))))
          {:status 200 :body {:ignored "another-game"}}

          (and (= event-type "checkout.session.completed")
               (= "paid" (or (:payment_status object) (get object "payment_status"))))
          (stripe-session-completed-response
           {:event-id event-id
            :connected-account-id connected-account-id
            :session object
            :submit-service-event submit-service-event
            :fallback-game-name fallback-game-name
            :verify-seller-payment verify-seller-payment
            :log-fn log-fn})

          ;; Refunds must be matched BEFORE the charge.updated backfill branch:
          ;; a refund also fires charge.updated, and the backfill branch would
          ;; otherwise swallow it.
          (#{"charge.refunded" "refund.updated" "refund.created"} event-type)
          (stripe-refund-response
           {:event-id event-id :event-type event-type :event-created event-created
            :charge (when (= event-type "charge.refunded") object)
            :refund (when (not= event-type "charge.refunded") object)
            :connected-account-id connected-account-id
            :verify-seller-payment verify-seller-payment
            :submit-service-event submit-service-event
            :fallback-game-name fallback-game-name
            :log-fn log-fn})

          (= event-type "invoice.paid")
          (stripe-subscription-invoice-paid-response
           {:event-id event-id :invoice object
            :connected-account-id connected-account-id
            :submit-service-event submit-service-event
            :fallback-game-name fallback-game-name
            :verify-seller-payment verify-seller-payment
            :log-fn log-fn})

          (= event-type "invoice.payment_failed")
          (let [subscription-id (or (:subscription object) (get object "subscription")
                                    (get-in object [:parent :subscription_details :subscription])
                                    (get-in object ["parent" "subscription_details" "subscription"]))
                metadata (session-metadata (or (:subscription_details object)
                                               (get object "subscription_details")
                                               (get-in object [:parent :subscription_details])
                                               (get-in object ["parent" "subscription_details"])
                                               object))
                adapter (some-> (metadata-value metadata :commerce-adapter) str str/lower-case)
                game-name (or (metadata-value metadata :game-name) (fallback-game-name))]
            (let [recorded (submit-service-event game-name "market/update-subscription-status"
                                                 "game-master" ["game-master"]
                                                 {:provider-subscription-id (str subscription-id)
                                                  :status "past-due"})
                  market-ok? (or (:success (gm-result recorded))
                                 (get (gm-result recorded) "success"))
                  delivered (when (and market-ok?
                                       (not (str/blank? (str adapter))))
                              (verify-seller-payment
                               {:event-kind "invoice-payment-failed"
                                :commerce-adapter adapter
                                :market-subscription-id (or (get-in (gm-result recorded) [:data :subscription-id])
                                                            (get-in (gm-result recorded) ["data" "subscription-id"]))
                                :stripe-subscription-id (str subscription-id)
                                :stripe-invoice-id (str (or (:id object) (get object "id")))
                                :stripe-event-id event-id
                                :connected-account-id connected-account-id
                                :status "past-due"}))]
              (if (and market-ok? (or (nil? delivered) (:ok? delivered)))
                {:status 200 :body {:status "past-due"}}
                {:status 503 :body {:error {:code "subscription-failure-delivery-failed"}}})))

          (= event-type "customer.subscription.updated")
          (stripe-subscription-updated-response
           {:event-id event-id :subscription object
            :connected-account-id connected-account-id
            :submit-service-event submit-service-event
            :fallback-game-name fallback-game-name
            :verify-seller-payment verify-seller-payment
            :log-fn log-fn})

          (= event-type "customer.subscription.deleted")
          (stripe-subscription-deleted-response
           {:event-id event-id :subscription object
            :connected-account-id connected-account-id
            :submit-service-event submit-service-event
            :fallback-game-name fallback-game-name
            :verify-seller-payment verify-seller-payment
            :log-fn log-fn})

          (#{"charge.dispute.created" "charge.dispute.closed"} event-type)
          (stripe-dispute-response
           {:event-id event-id :event-type event-type
            :dispute object
            :connected-account-id connected-account-id
            :verify-seller-payment verify-seller-payment
            :submit-service-event submit-service-event
            :fallback-game-name fallback-game-name
            :log-fn log-fn})

          (and (#{"charge.updated" "charge.succeeded"} event-type)
               (some? (:payment_intent object)))
          (stripe-charge-backfill-response
           {:event-id event-id
            :charge object
            :submit-service-event submit-service-event
            :fallback-game-name fallback-game-name
            :log-fn log-fn})

          (= event-type "account.updated")
          (stripe-account-updated-response
           {:account object
            :upsert-connect-account upsert-connect-account
            :update-connect-account-by-external-id
            (fn [fields]
              (update-connect-account-by-external-id (fallback-game-name) fields))
            :event-created event-created
            :log-fn log-fn})

          (= event-type "account.application.deauthorized")
          (stripe-account-deauthorized-response
           {:account-id connected-account-id
            :update-connect-account-by-external-id
            (fn [fields]
              (update-connect-account-by-external-id (fallback-game-name) fields))
            :event-created event-created
            :log-fn log-fn})

          (#{"transfer.reversed" "transfer.failed"} event-type)
          (stripe-transfer-reversed-response
           {:event-type event-type
            :transfer object
            :submit-service-event submit-service-event
            :log-fn log-fn})

          :else
          {:status 200 :body {:ignored event-type}})))))
