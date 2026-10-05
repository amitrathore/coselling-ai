(ns coselling-ai.seller-commerce
  "Runtime registry and HTTP client for Seller Commerce API v2.

   Market stores public adapter capabilities. This namespace owns private
   routing and credentials. Adding a seller must not add a new code branch."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]))

(defn- env [name]
  (some-> (System/getenv name) str/trim not-empty))

(defn- normalize-base-url [value]
  (some-> value (str/replace #"/+$" "")))

(defn- configured-adapters []
  (when-let [raw (env "SELLER_COMMERCE_ADAPTERS_JSON")]
    (try
      (let [parsed (json/parse-string raw true)]
        (if (map? parsed) parsed {}))
      (catch Exception _ {}))))

(defn- built-in-adapters []
  (cond-> {}
    (or (env "YVATAR_INTERNAL_URL") (env "YVATAR_BASE_URL"))
    (assoc :yvatar
           {:id "yvatar"
            :display-name "Yvatar"
            :base-url (or (env "YVATAR_INTERNAL_URL") (env "YVATAR_BASE_URL"))
            :service-token (or (env "YVATAR_MARKETPLACE_SERVICE_TOKEN")
                               (env "YVATAR_SERVICE_TOKEN"))
            :stripe-account-id (env "YVATAR_STRIPE_ACCOUNT_ID")
            :fallback-seller (or (env "YVATAR_STRIPE_FALLBACK_SELLER") "yvatar-seller")
            :commission-bps 3500})

    (env "LIAM_SELLER_BASE_URL")
    (assoc :liam
           {:id "liam"
            :display-name "Liam"
            :base-url (env "LIAM_SELLER_BASE_URL")
            :service-token (env "LIAM_SELLER_SERVICE_TOKEN")
            :stripe-account-id (env "LIAM_SELLER_STRIPE_ACCOUNT_ID")
            :fallback-seller (or (env "LIAM_SELLER_PLAYER") "liam-seller")
            :commission-bps 3000})))

(defn adapters
  "Private adapter registry, keyed by normalized adapter id. JSON entries win
   over convenience environment variables. Secrets never enter Market state."
  []
  (let [raw (merge (built-in-adapters) (or (configured-adapters) {}))]
    (into {}
          (map (fn [[id cfg]]
                 (let [id (keyword (name id))]
                   [id (-> cfg
                           (assoc :id (name id))
                           (update :base-url normalize-base-url))])))
          raw)))

(defn adapter [id]
  (get (adapters) (some-> id name str/lower-case keyword)))

(def ^:private default-commission-bps
  "Preview-program defaults remain available even when an adapter has no live
   checkout URL yet. They are private Coseller terms, not catalog fields."
  {:yvatar 3500
   :liam 3000})

(defn commission-bps
  "The private commission opportunity for an adapter. Runtime configuration
   wins; preview-only adapters fall back to their declared program default."
  [id]
  (let [key (some-> id name str/lower-case keyword)]
    (or (:commission-bps (get (adapters) key))
        (get default-commission-bps key))))

(defn public-profile [{:keys [id display-name]}]
  {:id id
   :display-name (or display-name id)
   :contract-version 2
   :checkout-ui "embedded"
   :billing-modes ["one-time" "recurring" "free"]
   :recipient-types ["email" "player" "none"]
   :fulfillment-mode "provider-outbox"})

(defn- request!
  ([cfg method path] (request! cfg method path nil nil))
  ([cfg method path body] (request! cfg method path body nil))
  ([cfg method path body extra-headers]
  (if (or (str/blank? (str (:base-url cfg)))
          (str/blank? (str (:service-token cfg))))
    {:ok? false :status 503
     :error {:code "seller-commerce-not-configured" :adapter (:id cfg)}}
    (try
      (let [options (cond-> {:headers (merge {"Authorization" (str "Bearer " (:service-token cfg))
                                              "Seller-Commerce-Version" "2"}
                                             extra-headers)
                             :as :json :coerce :unexceptional :throw-exceptions false}
                      (some? body) (assoc :content-type :json
                                          :body (json/generate-string body)))
            response ((case method :get http/get :post http/post)
                      (str (:base-url cfg) path) options)]
        (if (<= 200 (:status response) 299)
          {:ok? true :body (:body response)}
          {:ok? false :status (:status response) :error (:body response)}))
      (catch Exception e
        {:ok? false :status 502
         :error {:code "seller-commerce-unavailable" :message (.getMessage e)}})))))

(defn- compatible-manifest? [cfg manifest]
  (let [billing (or (:billing_modes manifest) (:billing-modes manifest))
        recipients (or (:recipient_types manifest) (:recipient-types manifest))
        events (or (:lifecycle_event_types manifest)
                   (:lifecycle-event-types manifest))]
    (and (= 2 (or (:contract_version manifest) (:contract-version manifest)))
       (= (str/lower-case (str (:id cfg)))
          (str/lower-case (str (or (:adapter_id manifest) (:adapter-id manifest)))))
       (contains? #{"test" "preview" "production"} (:environment manifest))
       (contains? #{"embedded" "redirect"}
                  (str (or (:checkout_ui manifest) (:checkout-ui manifest))))
       (= "provider-outbox"
          (str (or (:fulfillment_mode manifest) (:fulfillment-mode manifest))))
       (seq billing)
       (every? #{"one-time" "recurring" "free"} billing)
       (seq recipients)
       (every? #{"email" "player" "none"} recipients)
       (some #{"payment.confirmed"} events))))

(defn manifest!
  "Fetch and fail closed on the private adapter compatibility manifest."
  [cfg]
  (let [result (request! cfg :get "/seller-commerce/v2/manifest")]
    (if (and (:ok? result) (compatible-manifest? cfg (:body result)))
      result
      (if (:ok? result)
        {:ok? false :status 502
         :error {:code "seller-commerce-incompatible-manifest"
                 :adapter (:id cfg)}}
        result))))

(defn plan-sku-for-price!
  "Resolve a provider price reference through the seller's current catalog."
  [adapter-id price-ref]
  (when (and adapter-id (not (str/blank? (str price-ref))))
    (when-let [cfg (adapter adapter-id)]
      (let [compatible (manifest! cfg)
            result (when (:ok? compatible)
                     (request! cfg :get "/seller-commerce/v2/catalog"))]
        (when (:ok? result)
          (some (fn [item]
                  (when (= (str price-ref)
                           (str (or (:price_ref item) (:price-ref item))))
                    (:sku item)))
                (get-in result [:body :items])))))))

(defn prepare-checkout!
  [adapter-id request]
  (if-let [cfg (adapter adapter-id)]
    (let [compatible (manifest! cfg)
          result (when (:ok? compatible)
                   (request! cfg :post "/seller-commerce/v2/checkouts/prepare" request))
          body (:body result)]
      (if-not (:ok? compatible)
        compatible
        (if (:ok? result)
        (assoc result
               :fulfillment-ref (or (:fulfillment_ref body) (:fulfillment-ref body))
               :recipient (:recipient body))
        result)))
    {:ok? false :status 404
     :error {:code "seller-commerce-adapter-not-configured" :adapter adapter-id}}))

(defn- lifecycle-type [payment]
  (case (:event-kind payment)
    "subscription-status" "subscription.status_changed"
    "invoice-payment-failed" "subscription.status_changed"
    "refund" "refund.settled"
    "chargeback" (if (:outcome payment) "chargeback.resolved" "chargeback.opened")
    (case (:billing-reason payment)
      "subscription_update" "subscription.plan_changed"
      "subscription_cycle" "subscription.renewed"
      "payment.confirmed")))

(defn- lifecycle-event-id [event-type payment]
  (str (or (when (= event-type "refund.settled")
             (:stripe-refund-id payment))
           (:stripe-event-id payment)
           (:stripe-invoice-id payment)
           (:stripe-refund-id payment)
           (:stripe-dispute-id payment)
           (when (= event-type "subscription.status_changed")
             (str (:stripe-subscription-id payment) ":" (:status payment)))
           (:checkout-id payment)
           (str event-type ":" (hash payment)))))

(defn- present-map [entries]
  (into {} (remove (comp nil? val)) entries))

(defn payment->lifecycle-event
  "Translate provider-specific webhook facts into the provider-neutral v2 wire shape."
  [payment]
  (let [event-type (lifecycle-type payment)
        event-id (lifecycle-event-id event-type payment)
        currency (some-> (:currency payment) str str/upper-case)
        money (when (and (integer? (:amount-total payment)) currency)
                {:amount (:amount-total payment) :currency currency})
        fee (when (and (integer? (:application-fee-amount payment)) currency)
              {:amount (:application-fee-amount payment) :currency currency})
        references (present-map
                    {:checkout_session (:stripe-session-id payment)
                     :payment_intent (:stripe-payment-intent-id payment)
                     :subscription (:stripe-subscription-id payment)
                     :invoice (:stripe-invoice-id payment)
                     :refund (:stripe-refund-id payment)
                     :dispute (:stripe-dispute-id payment)
                     :connected_account (:connected-account-id payment)})]
    (cond-> {:event_id event-id
             :event_type event-type
             :occurred_at (or (:occurred-at payment)
                              (.toString (java.time.Instant/now)))
             :market (present-map
                      {:checkout_id (:checkout-id payment)
                       :checkout_batch_id (:checkout-batch-id payment)
                       :order_ids (:order-ids payment)
                       :subscription_id (:market-subscription-id payment)
                       :buyer (:buyer payment)
                       :fulfillment_ref (:fulfillment-ref payment)})
             :payload (present-map
                       {:plan_sku (:plan-sku payment)
                        :status (:status payment)
                        :outcome (:outcome payment)
                        :billing_reason (:billing-reason payment)
                        :cancel_at_period_end (:cancel-at-period-end payment)
                        :amount (:amount payment)})}
      (or (:payment-provider payment) money fee (seq references))
      (assoc :payment (cond-> {:provider (or (:payment-provider payment) "stripe")
                               :status (or (:payment-status payment) (:status payment) "processed")
                               :provider_references references}
                        (:payment-mode payment) (assoc :mode (:payment-mode payment))
                        money (assoc :money money)
                        fee (assoc :application_fee fee))))))

(defn confirm-payment!
  [adapter-id payment]
  (if-let [cfg (adapter adapter-id)]
    (let [compatible (manifest! cfg)
          event (payment->lifecycle-event payment)]
      (if (:ok? compatible)
        (request! cfg :post "/seller-commerce/v2/lifecycle-events" event
                  {"Idempotency-Key" (:event_id event)})
        compatible))
    {:ok? false :status 404
     :error {:code "seller-commerce-adapter-not-configured" :adapter adapter-id}}))

(defn stripe-account-id [cfg seller account]
  (let [status (or (:status account) (get account "status"))
        account-type (or (:accountType account) (get account "accountType"))
        enabled (or (:paymentsEnabled account) (get account "paymentsEnabled"))
        stored (or (:externalId account) (get account "externalId"))]
    (if (and (= "enabled" status) (= "standard" account-type) enabled)
      stored
      (when (= (str/lower-case (str seller))
               (str/lower-case (str (:fallback-seller cfg))))
        (:stripe-account-id cfg)))))
