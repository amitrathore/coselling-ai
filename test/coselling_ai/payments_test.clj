(ns coselling-ai.payments-test
  (:require [clojure.test :refer :all]
            [game.gm.config :as gm-config]
            [coselling-ai.payments :as payments]
            [coselling-ai.seller-commerce :as seller-commerce]
            [coselling-ai.stripe]))

(deftest active-provider-defaults-to-stripe-when-stripe-is-enabled
  (with-redefs [payments/resolve-market-payment-provider (fn [] nil)
                gm-config/stripe-enabled? (fn [] true)]
    (is (= "stripe" (payments/active-provider-id)))))

(deftest active-provider-defaults-to-stub-without-stripe
  (with-redefs [payments/resolve-market-payment-provider (fn [] nil)
                gm-config/stripe-enabled? (fn [] false)]
    (is (= "stub" (payments/active-provider-id)))))

(deftest active-provider-falls-back-to-stub-when-stripe-is-selected-without-key
  (with-redefs [payments/resolve-market-payment-provider (fn [] "stripe")
                gm-config/stripe-enabled? (fn [] false)]
    (is (= "stub" (payments/active-provider-id)))))

(deftest active-provider-honors-explicit-stub
  (with-redefs [payments/resolve-market-payment-provider (fn [] "stub")
                gm-config/stripe-enabled? (fn [] true)]
    (is (= "stub" (payments/active-provider-id)))))

(deftest seller-connection-accepts-only-ready-standard-accounts
  (with-redefs [gm-config/resolve-stripe-secret-key (constantly "sk_test")
                coselling-ai.stripe/exchange-oauth-code
                (fn [_] {:ok? true :account-id "acct_standard"})
                coselling-ai.stripe/retrieve-account
                (fn [_] {:ok? true
                         :account {:id "acct_standard" :type "standard"
                                   :charges_enabled true :payouts_enabled true
                                   :capabilities {:card_payments "active"
                                                  :transfers "active"}}})]
    (let [result (payments/complete-seller-connection {:code "ac_123"})]
      (is (:ok? result))
      (is (= "acct_standard" (:external-id result)))
      (is (true? (:payments-enabled result)))))
  (with-redefs [gm-config/resolve-stripe-secret-key (constantly "sk_test")
                coselling-ai.stripe/exchange-oauth-code
                (fn [_] {:ok? true :account-id "acct_express"})
                coselling-ai.stripe/retrieve-account
                (fn [_] {:ok? true
                         :account {:id "acct_express" :type "express"
                                   :charges_enabled true :payouts_enabled true
                                   :capabilities {:card_payments "active"
                                                  :transfers "active"}}})]
    (is (= "seller-account-not-ready"
           (:error-code (payments/complete-seller-connection {:code "ac_123"}))))))

(deftest seller-connection-accepts-oauth-standard-account-only-in-test-mode
  (let [blank-account {:id "acct_blank" :type "standard"
                       :charges_enabled false :payouts_enabled false
                       :capabilities {}
                       :requirements {:currently_due ["business_profile.url"
                                                      "tos_acceptance.date"]
                                      :pending_verification []
                                      :disabled_reason "requirements.past_due"}}
        complete-with-key
        (fn [secret-key]
          (with-redefs [gm-config/resolve-stripe-secret-key (constantly secret-key)
                        coselling-ai.stripe/exchange-oauth-code
                        (fn [_] {:ok? true :account-id "acct_blank"})
                        coselling-ai.stripe/retrieve-account
                        (fn [_] {:ok? true :account blank-account})]
            (payments/complete-seller-connection {:code "ac_123"})))]
    (testing "Stripe's OAuth test accounts can run direct test charges despite readiness flags"
      (let [result (complete-with-key "sk_test_platform")]
        (is (:ok? result))
        (is (true? (:payments-enabled result)))
        (is (true? (:payouts-enabled result)))
        (is (true? (get-in result [:provider-details :test-mode-readiness-override])))))
    (testing "the same unready account is rejected in live mode"
      (is (= "seller-account-not-ready"
             (:error-code (complete-with-key "sk_live_platform")))))))

;; --- Connect webhook dispatch ---

(defn- noop-log [& _] nil)

;; --- Merchant account is required for the stripe provider, exempt for stub ---

(deftest stripe-provider-surfaces-merchant-account-required
  (testing "the adapter's specific error code is preserved, not masked"
    (with-redefs [gm-config/resolve-stripe-secret-key (fn [] "sk_test")]
      (let [result (payments/create-checkout-session
                    {:provider-id "stripe" :checkout-id "c1" :buyer "b1"
                     :game-name "the-js" :line-items []
                     :success-url "https://s" :cancel-url "https://c"
                     :on-behalf-of nil})]
        (is (false? (:ok? result)))
        (is (= "merchant-account-required" (:error-code result))
            "must not be flattened into stripe-session-create-failed")
        (is (= "stripe" (:provider result)))))))

(deftest stub-provider-is-exempt-from-the-merchant-requirement
  (testing "stub creates no processor charge, so there is no merchant to attribute"
    (let [result (payments/create-checkout-session
                  {:provider-id "stub" :checkout-id "c1" :buyer "b1"
                   :game-name "the-js" :line-items []
                   :success-url "https://s" :cancel-url "https://c"
                   :on-behalf-of nil})]
      (is (:ok? result))
      (is (true? (:stub result))))))

(deftest account-updated-marks-enabled-and-upserts
  (let [captured (atom nil)]
    (let [resp (payments/stripe-account-updated-response
                {:account {:id "acct_1"
                           :payouts_enabled true
                           :metadata {:owner-id "owner-1" :game-name "the-js"}}
                 :upsert-connect-account (fn [game-name owner-id fields]
                                           (reset! captured {:game-name game-name
                                                             :owner-id owner-id :fields fields})
                                           {:status 200 :body {:success true}})
                 :log-fn noop-log})]
      (is (= 200 (:status resp)))
      (is (= "enabled" (get-in resp [:body :status])))
      (is (= "the-js" (:game-name @captured)))
      (is (= "owner-1" (:owner-id @captured)))
      (is (= "acct_1" (get-in @captured [:fields :external-id])))
      (is (true? (get-in @captured [:fields :payouts-enabled]))))))

(deftest account-updated-house-is-ignored-and-left-to-mom
  ;; House accounts are owned end-to-end by MoM's /webhooks/stripe/treasury, which is
  ;; treasury-authoritative. The GM holds no treasury credential, so upserting from here
  ;; returned 403 — and the resulting 5xx invited Stripe to retry for days. ACK instead.
  (let [resp (payments/stripe-account-updated-response
              {:account {:id "acct_ig" :payouts_enabled true
                         :metadata {:account "@intergraph"}}
               :upsert-connect-account (fn [& _] (throw (ex-info "player upsert must not run" {})))
               :log-fn noop-log})]
    (is (= 200 (:status resp)) "must not 5xx — Stripe would retry a permanent condition")
    (is (= "house-account-handled-by-mom" (get-in resp [:body :ignored])))
    (is (= "@intergraph" (get-in resp [:body :account])))))

(deftest account-updated-ignores-when-metadata-missing
  (let [resp (payments/stripe-account-updated-response
              {:account {:id "acct_1" :payouts_enabled true :metadata {}}
               :upsert-connect-account (fn [& _] (throw (ex-info "should not be called" {})))
               :log-fn noop-log})]
    (is (= 200 (:status resp)))
    (is (= "missing-metadata" (get-in resp [:body :ignored])))))

(deftest standard-account-update-resolves-by-external-id
  (let [captured (atom nil)
        resp (payments/stripe-account-updated-response
              {:account {:id "acct_standard" :payouts_enabled true :charges_enabled true
                         :capabilities {:card_payments "active"} :metadata {}}
               :update-connect-account-by-external-id
               (fn [fields] (reset! captured fields) {:status 200 :body {:success true}})
               :event-created 42
               :log-fn noop-log})]
    (is (= 200 (:status resp)))
    (is (= "acct_standard" (:external-id @captured)))
    (is (true? (:card-payments-enabled @captured)))
    (is (= 42 (:last-provider-event-at @captured)))))

(deftest deauthorization-disables-standard-account
  (let [captured (atom nil)
        resp (payments/stripe-account-deauthorized-response
              {:account-id "acct_standard"
               :update-connect-account-by-external-id
               (fn [fields] (reset! captured fields) {:status 200 :body {:success true}})
               :event-created 43 :log-fn noop-log})]
    (is (= 200 (:status resp)))
    (is (= "disabled" (:status @captured)))
    (is (false? (:payouts-enabled @captured)))
    (is (false? (:charges-enabled @captured)))))

(deftest transfer-failed-reverses-withdrawal
  (let [captured (atom nil)]
    (let [resp (payments/stripe-transfer-reversed-response
                {:event-type "transfer.failed"
                 :transfer {:id "tr_1"
                            :metadata {:withdrawal-id "wd-1" :game-name "the-js" :player-name "alice"}}
                 :submit-service-event (fn [game-name event-type player-name roles data]
                                         (reset! captured {:game-name game-name :event-type event-type
                                                           :player-name player-name :data data})
                                         {:status 200 :body {}})
                 :log-fn noop-log})]
      (is (= 200 (:status resp)))
      (is (= "withdrawal/reverse" (:event-type @captured)))
      (is (= "alice" (:player-name @captured)))
      (is (= "wd-1" (get-in @captured [:data :withdrawal-id]))))))

;; --- Refund & dispute webhooks ---

(defn- stub-payment-intent
  "Stub the PaymentIntent lookup the refund/dispute paths use to resolve a
   checkout, since a Stripe-dashboard refund carries no market metadata."
  [metadata f]
  (with-redefs [coselling-ai.stripe/fetch-payment-intent
                (fn [_] {:ok? true :payment-intent {:id "pi_1" :metadata metadata}})
                gm-config/resolve-stripe-secret-key (constantly "sk_test")]
    (f)))

(def ^:private pi-metadata {:checkout-id "checkout-1" :buyer "buyer_01" :game-name "the-js"})

(deftest refund-with-our-metadata-settles-that-refund
  (let [events (atom [])]
    (stub-payment-intent
     pi-metadata
     (fn []
       (let [resp (payments/stripe-refund-response
                   {:event-type "charge.refunded"
                    :charge {:payment_intent "pi_1"
                             :refunds {:data [{:id "re_1"
                                               :amount 2500
                                               :metadata {:refund-id "refund-9f2c"
                                                          :order-id "order-1"}}]}}
                    :submit-service-event (fn [_ event-type _ _ data]
                                            (swap! events conj {:type event-type :data data})
                                            {:body {:gm-result {:success true}}})
                    :fallback-game-name (constantly "the-js")
                    :log-fn noop-log})]
         (is (= 200 (:status resp)))
         (is (= "settled" (get-in resp [:body :status])))
         (is (= ["market/mark-refund-settled"] (mapv :type @events)))
         (is (= "refund-9f2c" (get-in (first @events) [:data :refund-id])))
         (is (= "re_1" (get-in (first @events) [:data :stripe-refund-id]))))))))

(deftest pending-refund-does-not-settle-or-notify-seller
  (let [events (atom [])]
    (stub-payment-intent
     pi-metadata
     (fn []
       (let [response (payments/stripe-refund-response
                       {:event-type "refund.created"
                        :refund {:id "re_pending" :payment_intent "pi_1"
                                 :status "pending" :amount 2500
                                 :metadata {:refund-id "refund-1"
                                            :order-id "order-1"}}
                        :submit-service-event
                        (fn [& args] (swap! events conj args))
                        :fallback-game-name (constantly "the-js")
                        :log-fn noop-log})]
         (is (= "refund-pending" (get-in response [:body :status])))
         (is (empty? @events)))))))

(deftest direct-refund-reconciles-fee-before-seller-notification
  (let [events (atom [])]
    (with-redefs [coselling-ai.stripe/fetch-payment-intent
                  (fn [_] {:ok? true :payment-intent {:metadata
                                                      (assoc pi-metadata
                                                             :commerce-adapter "liam")}})
                  coselling-ai.stripe/refund-application-fee
                  (fn [opts]
                    (swap! events conj [:fee opts])
                    {:ok? true :id "fr_1"})
                  gm-config/resolve-stripe-secret-key (constantly "sk_test")]
      (let [response (payments/stripe-refund-response
                      {:event-type "refund.updated"
                       :event-id "evt_1"
                       :refund {:id "re_1" :payment_intent "pi_1"
                                :status "succeeded" :amount 2500
                                :metadata {:refund-id "refund-1"
                                           :order-id "order-1"}}
                       :connected-account-id "acct_liam"
                       :submit-service-event
                       (fn [_ event-type _ _ _]
                         (swap! events conj [:market event-type])
                         {:body {:gm-result
                                 {:success true
                                  :data (when (= event-type "market/mark-refund-settled")
                                          {:fee-adjustment-status :pending
                                           :fee-adjustment-amount 750
                                           :refund-id "refund-1"
                                           :order-id "order-1"})}}})
                       :verify-seller-payment
                       (fn [_] (swap! events conj [:seller]) {:ok? true})
                       :fallback-game-name (constantly "the-js")
                       :log-fn noop-log})]
        (is (= 200 (:status response)))
        (is (= [[:market "market/mark-refund-settled"]
                [:fee {:secret-key "sk_test" :payment-intent-id "pi_1"
                       :connected-account-id "acct_liam" :amount 750
                       :refund-id "refund-1"}]
                [:market "market/mark-application-fee-refunded"]
                [:seller]]
               @events))))))

(deftest subscription-renewal-refund-resolves-market-checkout-by-invoice
  (let [events (atom [])]
    (with-redefs [coselling-ai.stripe/fetch-payment-intent
                  (fn [_] {:ok? true
                           :payment-intent {:invoice "in_renewal"
                                            :metadata {:game-name "the-js"}}})
                  gm-config/resolve-stripe-secret-key (constantly "sk_test")]
      (let [response (payments/stripe-refund-response
                      {:event-type "refund.updated"
                       :refund {:id "re_renewal" :status "succeeded"
                                :payment_intent "pi_renewal" :amount 1900}
                       :connected-account-id "acct_liam"
                       :submit-service-event
                       (fn [_ event-type _ _ data]
                         (swap! events conj [event-type data])
                         (case event-type
                           "market/query-checkouts"
                           {:body {:gm-result {:success true
                                               :data {:checkouts
                                                      [{:id "checkout-renewal"
                                                        :buyer "buyer_01"
                                                        :commerce-adapter "liam"}]}}}}
                           {:body {:gm-result {:success true}}}))
                       :verify-seller-payment (constantly {:ok? true})
                       :fallback-game-name (constantly "the-js")
                       :log-fn noop-log})]
        (is (= 200 (:status response)))
        (is (= "in_renewal" (get-in @events [0 1 :filters :invoice-id])))
        (is (= "checkout-renewal" (get-in @events [1 1 :checkout-id])))))))

(deftest subscription-initial-payment-refund-resolves-invoice-parent-metadata
  (let [events (atom [])]
    (with-redefs [coselling-ai.stripe/fetch-payment-intent
                  (fn [_] {:ok? true :payment-intent {:id "pi_initial" :metadata {}}})
                  coselling-ai.stripe/fetch-invoice-for-payment-intent
                  (fn [_] {:ok? true
                           :invoice {:id "in_initial"
                                     :parent {:subscription_details
                                              {:metadata {:game-name "agents-of-mind"
                                                          :checkout-id "checkout-initial"
                                                          :buyer "buyer_01"
                                                          :commerce-adapter "liam"}}}}})
                  gm-config/resolve-stripe-secret-key (constantly "sk_test")]
      (let [response (payments/stripe-refund-response
                      {:event-type "refund.updated"
                       :event-id "evt_initial_refund"
                       :refund {:id "re_initial" :status "succeeded"
                                :payment_intent "pi_initial" :amount 1000}
                       :connected-account-id "acct_liam"
                       :submit-service-event
                       (fn [game-name event-type _ _ data]
                         (swap! events conj [game-name event-type data])
                         (case event-type
                           "market/query-checkouts"
                           {:body {:gm-result {:success true :data {:checkouts []}}}}
                           {:body {:gm-result {:success true :data {:refunds []}}}}))
                       :verify-seller-payment (constantly {:ok? true})
                       :fallback-game-name (constantly "wrong-game")
                       :log-fn noop-log})]
        (is (= 200 (:status response)))
        (is (= "external-refund-recorded" (get-in response [:body :status])))
        (is (= "in_initial" (get-in @events [0 2 :filters :invoice-id])))
        (is (= "agents-of-mind" (get-in @events [1 0])))
        (is (= "checkout-initial" (get-in @events [1 2 :checkout-id])))))))

(deftest charge-refunded-without-embedded-refunds-fetches-the-refund-objects
  (let [events (atom [])]
    (with-redefs [coselling-ai.stripe/fetch-charge-refunds
                  (fn [opts]
                    (is (= "ch_1" (:charge-id opts)))
                    {:ok? true :refunds [{:id "re_1" :amount 1000
                                          :status "succeeded"}]})
                  coselling-ai.stripe/fetch-payment-intent
                  (fn [_] {:ok? true :payment-intent {:metadata pi-metadata}})
                  gm-config/resolve-stripe-secret-key (constantly "sk_test")]
      (let [response (payments/stripe-refund-response
                      {:event-type "charge.refunded" :event-id "evt_1"
                       :charge {:id "ch_1" :payment_intent "pi_1"
                                :amount_refunded 1000}
                       :submit-service-event
                       (fn [_ event-type _ _ data]
                         (swap! events conj [event-type data])
                         {:body {:gm-result {:success true :data {:refunds []}}}})
                       :fallback-game-name (constantly "the-js")
                       :log-fn noop-log})]
        (is (= 200 (:status response)))
        (is (= "charge-refunds-reconciled" (get-in response [:body :status])))
        (is (= "re_1" (get-in @events [0 1 :stripe-refund-id])))))))

(deftest webhook-preserves-refund-occurrence-time-for-stable-seller-replay
  (let [delivered (atom nil)]
    (with-redefs [coselling-ai.stripe/verify-signature
                  (fn [& _] {:ok? true})
                  coselling-ai.stripe/parse-event
                  (fn [_] {:type "refund.updated" :id "evt_updated"
                           :created 1700000300 :data {:object {}}})
                  payments/stripe-refund-response
                  (fn [{:keys [verify-seller-payment]}]
                    (verify-seller-payment {:occurred-at "2023-11-14T22:13:20Z"})
                    {:status 200 :body {}})]
      (is (= 200 (:status
                  (payments/handle-stripe-webhook
                   {:raw-body "{}" :signature "test"
                    :webhook-secrets ["whsec_test"]
                    :verify-seller-payment #(reset! delivered %)
                    :log-fn noop-log}))))
      (is (= "2023-11-14T22:13:20Z" (:occurred-at @delivered))))))

(deftest refund-webhook-redelivery-is-idempotent
  (stub-payment-intent
   pi-metadata
   (fn []
     (let [resp (payments/stripe-refund-response
                 {:event-type "charge.refunded"
                  :charge {:payment_intent "pi_1"
                           :refunds {:data [{:id "re_1" :amount 2500
                                             :metadata {:refund-id "refund-9f2c"}}]}}
                  ;; the orchestration already settled it
                  :submit-service-event (fn [& _]
                                          {:body {:gm-result
                                                  {:success false
                                                   :error {:code "refund-not-reserved"}}}})
                  :fallback-game-name (constantly "the-js")
                  :log-fn noop-log})]
       (is (= 200 (:status resp)))
       (is (= "already-settled" (get-in resp [:body :status])))))))

(deftest dashboard-refund-is-reconciled-as-external
  (let [events (atom [])]
    (stub-payment-intent
     pi-metadata
     (fn []
       (let [resp (payments/stripe-refund-response
                   {:event-type "charge.refunded"
                    ;; no metadata[refund-id] — issued in the Stripe dashboard
                    :charge {:payment_intent "pi_1"
                             :amount_refunded 4000
                             :refunds {:data [{:id "re_ext" :amount 4000}]}}
                    :submit-service-event (fn [_ event-type _ _ data]
                                            (swap! events conj {:type event-type :data data})
                                            {:body {:gm-result {:success true
                                                                :data {:refunds [{:order-id "order-1"
                                                                                 :refund-id "refund-1"
                                                                                 :status "reserving"}]}}
                                                    :effect-results [{:status "succeeded"}]}})
                    :fallback-game-name (constantly "the-js")
                    :log-fn noop-log})]
         (is (= 200 (:status resp)))
         (is (= "external-refund-recorded" (get-in resp [:body :status])))
         (is (= ["market/record-external-refund" "market/mark-refund-settled"]
                (mapv :type @events)))
         (is (= "checkout-1" (get-in (first @events) [:data :checkout-id])))
         (is (= 4000 (get-in (first @events) [:data :amount])))
         (is (= "re_ext" (get-in (first @events) [:data :stripe-refund-id]))))))))

(deftest unresolvable-refund-webhook-is-ignored-not-errored
  (stub-payment-intent
   {}
   (fn []
     (let [resp (payments/stripe-refund-response
                 {:event-type "charge.refunded"
                  :charge {:payment_intent "pi_1" :amount_refunded 4000
                           :refunds {:data [{:id "re_ext" :amount 4000}]}}
                  :submit-service-event (fn [& _] (throw (ex-info "must not submit" {})))
                  :fallback-game-name (constantly "the-js")
                  :log-fn noop-log})]
       (is (= 200 (:status resp)))
       (is (= "missing-metadata" (get-in resp [:body :ignored])))))))

(deftest dispute-created-freezes-without-moving-the-ledger
  (let [events (atom [])]
    (stub-payment-intent
     pi-metadata
     (fn []
       (let [resp (payments/stripe-dispute-response
                   {:event-type "charge.dispute.created"
                    :dispute {:id "dp_1" :payment_intent "pi_1" :amount 10000
                              :status "needs_response" :reason "fraudulent"}
                    :submit-service-event (fn [_ event-type _ _ data]
                                            (swap! events conj {:type event-type :data data})
                                            {:body {:gm-result {:success true}}})
                    :fallback-game-name (constantly "the-js")
                    :log-fn noop-log})]
         (is (= 200 (:status resp)))
         (is (= "dispute-recorded" (get-in resp [:body :status])))
         (is (= ["market/record-chargeback"] (mapv :type @events))
             "an opened dispute records and freezes only — no refund yet")
         (is (= "dp_1" (get-in (first @events) [:data :stripe-dispute-id])))
         (is (= 10000 (get-in (first @events) [:data :amount]))))))))

(deftest subscription-initial-payment-dispute-resolves-invoice-parent-metadata
  (let [events (atom [])]
    (with-redefs [coselling-ai.stripe/fetch-payment-intent
                  (fn [_] {:ok? true :payment-intent {:id "pi_initial" :metadata {}}})
                  coselling-ai.stripe/fetch-invoice-for-payment-intent
                  (fn [_] {:ok? true
                           :invoice {:id "in_initial"
                                     :parent {:subscription_details
                                              {:metadata {:game-name "agents-of-mind"
                                                          :checkout-id "checkout-initial"
                                                          :commerce-adapter "liam"}}}}})
                  gm-config/resolve-stripe-secret-key (constantly "sk_test")]
      (let [response (payments/stripe-dispute-response
                      {:event-id "evt_dispute"
                       :event-type "charge.dispute.created"
                       :dispute {:id "dp_initial" :payment_intent "pi_initial"
                                 :amount 4900 :status "needs_response"
                                 :reason "product_not_received"}
                       :connected-account-id "acct_liam"
                       :submit-service-event
                       (fn [game-name event-type _ _ data]
                         (swap! events conj [game-name event-type data])
                         {:body {:gm-result {:success true :data {:checkouts []}}}})
                       :verify-seller-payment (constantly {:ok? true})
                       :fallback-game-name (constantly "wrong-game")
                       :log-fn noop-log})]
        (is (= 200 (:status response)))
        (is (= "dispute-recorded" (get-in response [:body :status])))
        (is (= "in_initial" (get-in @events [0 2 :filters :invoice-id])))
        (is (= "agents-of-mind" (get-in @events [1 0])))
        (is (= "market/record-chargeback" (get-in @events [1 1])))
        (is (= "checkout-initial" (get-in @events [1 2 :checkout-id])))))))

(deftest dispute-won-unfreezes-without-a-refund
  (let [events (atom [])]
    (stub-payment-intent
     pi-metadata
     (fn []
       (let [resp (payments/stripe-dispute-response
                   {:event-type "charge.dispute.closed"
                    :dispute {:id "dp_1" :payment_intent "pi_1" :amount 10000 :status "won"}
                    :submit-service-event
                    (fn [_ event-type _ _ data]
                      (swap! events conj {:type event-type :data data})
                      {:body {:gm-result {:success true :data {:requires-refund false}}}})
                    :fallback-game-name (constantly "the-js")
                    :log-fn noop-log})]
         (is (= 200 (:status resp)))
         (is (= "dispute-won" (get-in resp [:body :status])))
         (is (= ["market/resolve-chargeback"] (mapv :type @events)))
         (is (= "won" (get-in (first @events) [:data :outcome]))))))))

(deftest dispute-lost-also-records-the-external-refund
  (let [events (atom [])]
    (stub-payment-intent
     pi-metadata
     (fn []
       (let [resp (payments/stripe-dispute-response
                   {:event-type "charge.dispute.closed"
                    :dispute {:id "dp_1" :payment_intent "pi_1" :amount 10000 :status "lost"}
                    :submit-service-event
                    (fn [_ event-type _ _ data]
                      (swap! events conj {:type event-type :data data})
                      {:body {:gm-result {:success true
                                          :data {:requires-refund true
                                                 :refunds [{:order-id "order-1"
                                                            :refund-id "refund-1"
                                                            :status "reserving"}]}}
                              :effect-results [{:status "succeeded"}]}})
                    :fallback-game-name (constantly "the-js")
                    :log-fn noop-log})]
         (is (= 200 (:status resp)))
         (is (= "dispute-lost" (get-in resp [:body :status])))
         (is (= ["market/resolve-chargeback" "market/record-external-refund"
                 "market/mark-refund-settled"]
                (mapv :type @events))
             "a lost dispute is money that really left, so it is reconciled too")
         (is (= "dispute:dp_1" (get-in (second @events) [:data :stripe-refund-id]))
             "deduped on the dispute id, so a redelivery cannot double-refund")
         (is (= 10000 (get-in (second @events) [:data :amount]))))))))

(deftest webhook-dispatch-routes-refunds-before-the-charge-backfill
  ;; A refund also fires charge.updated; if the backfill branch were matched
  ;; first it would swallow charge.refunded entirely.
  (let [calls (atom [])]
    (with-redefs [coselling-ai.stripe/verify-signature (fn [& _] {:ok? true})
                  coselling-ai.stripe/parse-event
                  (fn [raw] {:type raw :id "evt_1"
                             :data {:object {:payment_intent "pi_1"
                                             :id "dp_1" :status "won" :amount 100
                                             :refunds {:data [{:id "re_1" :metadata {:refund-id "r1"}}]}}}})
                  gm-config/resolve-stripe-webhook-secret (constantly "whsec")
                  payments/stripe-refund-response (fn [_] (swap! calls conj :refund) {:status 200 :body {}})
                  payments/stripe-dispute-response (fn [_] (swap! calls conj :dispute) {:status 200 :body {}})
                  payments/stripe-charge-backfill-response
                  (fn [_] (swap! calls conj :backfill) {:status 200 :body {}})]
      (doseq [event-type ["charge.refunded" "refund.updated"
                          "charge.dispute.created" "charge.dispute.closed"
                          "charge.updated"]]
        (payments/handle-stripe-webhook
         {:raw-body event-type :signature "sig"
          :submit-service-event (fn [& _] {:body {}})
          :fallback-game-name (constantly "the-js")
          :log-fn noop-log}))
      (is (= [:refund :refund :dispute :dispute :backfill] @calls)))))

(deftest webhook-ignores-unrelated-event-types
  ;; This used to use invoice.paid as its example of an unrelated event. It is
  ;; now handled (subscription renewals), so the test needs a type we genuinely
  ;; do not care about — otherwise it would quietly assert the opposite of what
  ;; the webhook does.
  (with-redefs [coselling-ai.stripe/verify-signature (fn [& _] {:ok? true})
                coselling-ai.stripe/parse-event (fn [_] {:type "customer.created" :id "evt_2" :data {:object {}}})
                gm-config/resolve-stripe-webhook-secret (constantly "whsec")]
    (let [resp (payments/handle-stripe-webhook
                {:raw-body "{}" :signature "sig"
                 :submit-service-event (fn [& _] (throw (ex-info "must not submit" {})))
                 :fallback-game-name (constantly "the-js")
                 :log-fn noop-log})]
      (is (= 200 (:status resp)))
      (is (= "customer.created" (get-in resp [:body :ignored]))))))

(deftest webhook-deauthorization-updates-the-current-game-account
  (let [captured (atom nil)]
    (with-redefs [coselling-ai.stripe/verify-signature (fn [& _] {:ok? true})
                  coselling-ai.stripe/parse-event
                  (fn [_] {:type "account.application.deauthorized" :id "evt_disconnect"
                           :created 44 :account "acct_standard" :data {:object {}}})
                  gm-config/resolve-stripe-webhook-secret (constantly "whsec")]
      (let [resp (payments/handle-stripe-webhook
                  {:raw-body "{}" :signature "sig"
                   :submit-service-event (fn [& _] {:body {}})
                   :fallback-game-name (constantly "the-js")
                   :update-connect-account-by-external-id
                   (fn [game-name fields]
                     (reset! captured {:game-name game-name :fields fields})
                     {:status 200 :body {:success true}})
                   :log-fn noop-log})]
        (is (= 200 (:status resp)))
        (is (= "the-js" (:game-name @captured)))
        (is (= "disabled" (get-in @captured [:fields :status])))))))

;; --- Merchant attribution verification at the webhook (hard-merchant-gate §5) ---
;;
;; The session stamps merchant-account-id into PaymentIntent metadata (§3). At
;; webhook time we compare that stamp against the PaymentIntent's ACTUAL
;; on_behalf_of. The rule keys on the STAMP, not on on_behalf_of: a session
;; created before the gate shipped has neither, and quarantining it would charge
;; the buyer and then strand the order forever.

(defn- session-completed
  "Drives stripe-session-completed-response with a stubbed PaymentIntent."
  [{:keys [pi-metadata on-behalf-of gm-result fetch-result session]}]
  (let [submitted (atom [])
        logs (atom [])]
    (with-redefs [coselling-ai.stripe/fetch-payment-intent
                  (fn [_] (or fetch-result
                              {:ok? true
                               :payment-intent (cond-> {:id "pi_1" :metadata pi-metadata}
                                                 on-behalf-of (assoc :on_behalf_of on-behalf-of))}))
                  gm-config/resolve-stripe-secret-key (constantly "sk_test")]
      (let [resp (payments/stripe-session-completed-response
                  {:event-id "evt_1"
                   :session (merge {:id "cs_1" :payment_intent "pi_1"
                                    :amount_total 5000 :currency "usd"
                                    :metadata {:checkout-id "checkout-1" :buyer "buyer_01"
                                               :game-name "the-js"}}
                                   session)
                   :submit-service-event
                   (fn [_ event-type _ _ data]
                     (swap! submitted conj {:type event-type :data data})
                     {:body {:gm-result (or gm-result {:success true})}})
                   :fallback-game-name (constantly "the-js")
                   :log-fn (fn [level msg & [data]]
                             (swap! logs conj {:level level :msg msg :data data}))})]
        {:response resp :submitted @submitted :logs @logs}))))

(deftest seller-direct-webhook-records-only-that-group-and-verifies-yvatar
  (let [submitted (atom [])
        verified (atom nil)
        response (with-redefs [coselling-ai.stripe/fetch-payment-intent
                               (fn [opts]
                                 (is (= "acct_actual_seller" (:connected-account-id opts)))
                                 {:ok? true :payment-intent {:application_fee_amount 350}})]
                   (payments/stripe-session-completed-response
                    {:event-id "evt_y"
                     :connected-account-id "acct_actual_seller"
                     :session {:id "cs_y" :payment_status "paid" :payment_intent "pi_y"
                               :livemode true
                               :amount_total 1000 :currency "usd"
                               :metadata {:checkout-id "checkout-y"
                                          :checkout-batch-id "batch-1"
                                          :buyer "buyer" :game-name "the-js"
                                          :commerce-adapter "yvatar"
                                          :fulfillment-ref "fulfill-1"}}
                     :submit-service-event
                     (fn [_ event-type actor roles data]
                       (swap! submitted conj {:type event-type :actor actor
                                              :roles roles :data data})
                       {:body {:gm-result {:success true}}})
                     :verify-seller-payment (fn [payment]
                                              (reset! verified payment)
                                              {:ok? true})
                     :fallback-game-name (constantly "the-js")
                     :log-fn noop-log}))]
    (is (= 200 (:status response)))
    (is (= ["market/record-seller-checkout-paid"] (mapv :type @submitted)))
    (is (= "game-master" (:actor (first @submitted))))
    (is (= "acct_actual_seller"
           (get-in (first @submitted) [:data :payment-ref :connected-account])))
    (is (= "checkout-y" (:checkout-id @verified)))
    (is (= "fulfill-1" (:fulfillment-ref @verified)))
    (is (= "live" (:payment-mode @verified)))
    (is (= 350 (:application-fee-amount @verified)))
    (is (= "acct_actual_seller" (:connected-account-id @verified)))))

(deftest seller-direct-webhook-dispatch-forwards-the-verification-callback
  ;; The route supplies this callback to handle-stripe-webhook. Dropping it while
  ;; dispatching the parsed event records the payment and then throws before
  ;; Yvatar can grant the credits.
  (let [verified (atom nil)
        session {:id "cs_y" :payment_status "paid" :payment_intent "pi_y"
                 :livemode false
                 :amount_total 997 :currency "usd"
                 :metadata {:checkout-id "checkout-y"
                            :checkout-batch-id "batch-y"
                            :buyer "buyer" :game-name "the-js"
                            :commerce-adapter "yvatar"
                            :fulfillment-ref "fulfill-y"}}]
    (with-redefs [gm-config/resolve-stripe-webhook-secret (constantly "whsec_test")
                  coselling-ai.stripe/verify-signature (fn [& _] {:ok? true})
                  coselling-ai.stripe/fetch-payment-intent
                  (fn [_] {:ok? true :payment-intent {:application_fee_amount 349}})
                  coselling-ai.stripe/parse-event
                  (fn [_] {:id "evt_y" :type "checkout.session.completed"
                           :created 1790688367
                           :account "acct_y" :data {:object session}})]
      (let [response
            (payments/handle-stripe-webhook
             {:raw-body "{}" :signature "valid"
              :submit-service-event (fn [& _] {:body {:gm-result {:success true}}})
              :fallback-game-name (constantly "the-js")
              :verify-seller-payment (fn [payment]
                                       (reset! verified payment)
                                       {:ok? true})
              :log-fn noop-log})]
        (is (= 200 (:status response)))
        (is (= "checkout-y" (:checkout-id @verified)))
        (is (= "2026-09-29T13:26:07Z" (:occurred-at @verified)))
        (is (= "fulfill-y" (:fulfillment-ref @verified)))
        (is (= "test" (:payment-mode @verified)))))))

(deftest fee-not-yet-available-is-informational-not-a-warning
  ;; Stripe creates the balance transaction asynchronously, so at
  ;; checkout.session.completed time the fee is often absent even on a healthy
  ;; 200. charge.updated backfills it moments later and settlement uses the real
  ;; figure. Logging that as "platform absorbs fee" describes a transient state as
  ;; a final outcome and sends an operator hunting for lost money at 2am.
  (let [{:keys [logs]} (session-completed {:pi-metadata {:checkout-id "checkout-1"}})
        fee-logs (filter #(re-find #"(?i)fee" (str (:msg %))) logs)]
    (is (seq fee-logs) "the missing fee is still recorded")
    (is (every? #(= "INFO" (:level %)) fee-logs)
        "a pending backfill is not a warning")
    (is (not-any? #(re-find #"(?i)absorb" (str (:msg %))) fee-logs)
        "must not claim the platform absorbed a fee that will be backfilled")))

(deftest fee-fetch-failure-is-still-a-warning
  ;; A genuine failure — non-200, or missing credentials — really can mean the fee
  ;; is never recovered, and the platform really does eat it. That stays a WARN.
  (let [{:keys [logs]} (session-completed {:pi-metadata {:checkout-id "checkout-1"}
                                           :fetch-result {:ok? false :status 502
                                                          :error {:message "boom"}}})
        fee-logs (filter #(re-find #"(?i)fee" (str (:msg %))) logs)]
    (is (some #(= "WARN" (:level %)) fee-logs)
        "a real fetch failure must still warn")
    (is (some #(= 502 (get-in % [:data :status])) fee-logs)
        "and must carry the failing status for diagnosis")))

(deftest session-completed-legacy-unstamped-is-paid-and-audited
  (testing "a pre-gate session has no stamp; it must still be marked paid"
    (let [{:keys [response submitted logs]}
          (session-completed {:pi-metadata {:checkout-id "checkout-1"}})]
      (is (= 200 (:status response)))
      (is (= "paid" (get-in response [:body :status])))
      (is (= ["market/mark-checkout-paid"] (mapv :type submitted))
          "the buyer was charged; stranding the order would be worse than not verifying")
      (is (some #(re-find #"(?i)legacy|unstamped" (str (:msg %))) logs)
          "should record informationally that this one could not be verified"))))

(deftest session-completed-matching-attribution-is-paid-and-recorded
  (let [{:keys [response submitted]}
        (session-completed {:pi-metadata {:merchant-account-id "acct_game"}
                            :on-behalf-of "acct_game"})]
    (is (= 200 (:status response)))
    (is (= "paid" (get-in response [:body :status])))
    (testing "the merchant account is recorded on the payment reference"
      (is (= "acct_game" (get-in (first submitted) [:data :payment-ref :merchant-account-id]))))))

(deftest session-completed-records-gross-charge-and-discount-audit-data
  (let [{:keys [submitted]}
        (session-completed
          {:pi-metadata {:merchant-account-id "acct_game"}
           :on-behalf-of "acct_game"
           :session {:amount_total 4000
                     :currency "usd"
                     :total_details {:amount_discount 1000}
                     :discounts [{:promotion_code "promo_save20"}]}})
        payment-ref (get-in (first submitted) [:data :payment-ref])]
    (is (= {:amount 4000 :currency "USD"} (:amount payment-ref)))
    (is (= {:amount 1000 :currency "USD"} (:discount payment-ref)))
    (is (= "promo_save20" (:promotion-code-id payment-ref)))))

(deftest session-completed-quarantines-a-mismatch
  (testing "stamped one merchant, charged another"
    (let [{:keys [response submitted logs]}
          (session-completed {:pi-metadata {:merchant-account-id "acct_expected"}
                              :on-behalf-of "acct_someone_else"})]
      (is (empty? submitted) "must not mark paid or mint ledger funding")
      (is (= 200 (:status response))
          "200 deliberately: a mismatch is permanent, and a non-2xx makes Stripe retry for days")
      (is (= "quarantined" (get-in response [:body :status])))
      (is (some #(= "ERROR" (:level %)) logs) "must be high severity for an operator"))))

(deftest session-completed-quarantines-a-stamped-session-with-no-attribution
  (testing "stamped, but the charge carries no on_behalf_of at all"
    (let [{:keys [response submitted]}
          (session-completed {:pi-metadata {:merchant-account-id "acct_expected"}})]
      (is (empty? submitted))
      (is (= 200 (:status response)))
      (is (= "quarantined" (get-in response [:body :status]))))))

(deftest session-completed-replay-stays-idempotent
  (testing "a verified event redelivered is still an idempotent already-paid"
    (let [{:keys [response]}
          (session-completed {:pi-metadata {:merchant-account-id "acct_game"}
                              :on-behalf-of "acct_game"
                              :gm-result {:success false :error {:code "checkout-already-paid"}}})]
      (is (= 200 (:status response)))
      (is (= "already-paid" (get-in response [:body :status]))))))
;; --- Startup provider assertion (hard-merchant-gate §6.2) ---
;;
;; active-provider-id silently downgrades an explicitly configured "stripe" to
;; "stub" when no key is set. A misconfigured production would then produce fake
;; successful checkouts -- buyers see success, no money moves, no error anywhere.
;; "Production should use Stripe" is not an enforcement mechanism.

(deftest provider-assertion-passes-when-stripe-is-properly-configured
  (with-redefs [payments/resolve-market-payment-provider (constantly "stripe")
                gm-config/stripe-enabled? (constantly true)]
    (is (nil? (payments/assert-provider-configured!)))))

(deftest provider-assertion-passes-for-explicit-stub
  (with-redefs [payments/resolve-market-payment-provider (constantly "stub")
                gm-config/stripe-enabled? (constantly false)]
    (is (nil? (payments/assert-provider-configured!))
        "explicitly asking for stub is a deliberate choice, not a misconfiguration")))

(deftest provider-assertion-passes-when-nothing-is-configured
  (with-redefs [payments/resolve-market-payment-provider (constantly nil)
                gm-config/stripe-enabled? (constantly false)]
    (is (nil? (payments/assert-provider-configured!))
        "an unconfigured local dev stack defaults to stub without complaint")))

(deftest provider-assertion-fails-when-stripe-is-asked-for-without-a-key
  (testing "the silent-downgrade case: explicitly stripe, but no key"
    (with-redefs [payments/resolve-market-payment-provider (constantly "stripe")
                  gm-config/stripe-enabled? (constantly false)]
      (let [ex (is (thrown? clojure.lang.ExceptionInfo
                            (payments/assert-provider-configured!)))]
        (is (= :provider-misconfigured (:reason (ex-data ex))))
        (is (= "stripe" (:configured (ex-data ex))))
        (is (= "stub" (:active (ex-data ex))))))))

(deftest provider-assertion-is-case-insensitive
  (with-redefs [payments/resolve-market-payment-provider (constantly "STRIPE")
                gm-config/stripe-enabled? (constantly false)]
    (is (thrown? clojure.lang.ExceptionInfo (payments/assert-provider-configured!)))))

;; ── Subscriptions ───────────────────────────────────────────────────────────

(defn- renewal-resp
  "Drive the paid-invoice webhook, collecting the service events it submits."
  [invoice]
  (let [events (atom [])
        resp (payments/stripe-subscription-invoice-paid-response
               {:invoice invoice
                :submit-service-event (fn [_ event-type _ _ data]
                                        (swap! events conj {:type event-type :data data})
                                        {:body {:gm-result {:success true}}})
                :fallback-game-name (constantly "the-js")
                :log-fn noop-log})]
    [resp @events]))

(deftest the-first-cycle-invoice-is-ignored-so-nobody-is-double-billed
  ;; THE guard. checkout.session.completed already records cycle one; Stripe ALSO
  ;; fires invoice.paid for it, with billing_reason subscription_create. Acting on
  ;; both would mint two paid orders for one payment and pay commission twice.
  (let [[resp events] (renewal-resp {:id "in_first"
                                     :billing_reason "subscription_create"
                                     :subscription "sub_1"
                                     :subscription_details {:metadata {:game-name "the-js"}}})]
    (is (= 200 (:status resp)))
    (is (= "ignored" (get-in resp [:body :status])))
    (is (empty? events)
        "no service event may be submitted for the first cycle's invoice")))

(deftest a-renewal-cycle-records-the-renewal
  (let [[resp events] (renewal-resp {:id "in_002"
                                     :billing_reason "subscription_cycle"
                                     :subscription "sub_1"
                                     :charge "ch_002"
                                     :amount_paid 2400
                                     :currency "usd"
                                     :total_discount_amounts [{:amount 600}]
                                     :subscription_details {:metadata {:game-name "the-js"}}})]
    (is (= 200 (:status resp)))
    (is (= "paid" (get-in resp [:body :status])))
    (is (= ["market/record-subscription-invoice-paid"] (mapv :type events)))
    (is (= "sub_1" (get-in (first events) [:data :provider-subscription-id])))
    (is (= "in_002" (get-in (first events) [:data :invoice-id]))
        "the invoice id is what makes the renewal idempotent downstream")
    (is (= "ch_002" (get-in (first events) [:data :payment-ref :charge-id])))
    (is (= {:amount 2400 :currency "USD"}
           (get-in (first events) [:data :payment-ref :amount])))
    (is (= {:amount 600 :currency "USD"}
           (get-in (first events) [:data :payment-ref :discount])))))

(deftest seller-renewal-carries-the-new-market-order-into-v2-fulfillment
  (let [delivered (atom nil)
        invoice {:id "in_seller_2" :billing_reason "subscription_cycle"
                 :subscription "sub_seller" :amount_paid 4900 :currency "usd"
                 :lines {:data [{:amount 4900 :price {:id "price_plus"}}]}
                 :subscription_details {:metadata {:game-name "the-js"
                                                  :commerce-adapter "liam"
                                                  :checkout-id "checkout-original"
                                                  :checkout-batch-id "batch-original"
                                                  :fulfillment-ref "liam:checkout-original"
                                                  :buyer "buyer" :plan-sku "plus"}}}]
    (with-redefs [seller-commerce/plan-sku-for-price! (fn [_ _] "plus")]
      (let [response (payments/stripe-subscription-invoice-paid-response
                      {:event-id "evt_seller_2" :invoice invoice
                       :submit-service-event (fn [& _]
                                               {:body {:gm-result
                                                       {:success true
                                                        :data {:subscription-id "sub-market"
                                                               :checkout-id "checkout-cycle"
                                                               :order-id "order-cycle"}}}})
                       :verify-seller-payment (fn [payment]
                                                (reset! delivered payment) {:ok? true})
                       :fallback-game-name (constantly "the-js")
                       :log-fn noop-log})]
        (is (= 200 (:status response)))
        (is (= "checkout-cycle" (:checkout-id @delivered)))
        (is (= ["order-cycle"] (:order-ids @delivered)))
        (is (= "sub-market" (:market-subscription-id @delivered)))
        (is (= "plus" (:plan-sku @delivered)))))))

(deftest seller-invoice-replay-reconfirms-the-recorded-cycle-not-the-original-checkout
  (let [delivered (atom nil)
        invoice {:id "in_replayed" :billing_reason "subscription_update"
                 :subscription "sub_seller" :amount_paid 2875 :currency "usd"
                 :lines {:data [{:amount 2875 :price {:id "price_pro"}}]}
                 :subscription_details {:metadata {:game-name "the-js"
                                                  :commerce-adapter "liam"
                                                  :checkout-id "checkout-original"
                                                  :buyer "buyer"}}}]
    (with-redefs [seller-commerce/plan-sku-for-price! (fn [& _] "pro")]
      (let [response (payments/stripe-subscription-invoice-paid-response
                      {:event-id "evt_replayed" :invoice invoice
                       :submit-service-event
                       (fn [& _] {:body {:gm-result
                                         {:success true
                                          :data {:already-recorded true
                                                 :subscription-id "sub-market"
                                                 :checkout-id "checkout-cycle"
                                                 :order-id "order-cycle"}}}})
                       :verify-seller-payment (fn [payment]
                                                (reset! delivered payment) {:ok? true})
                       :fallback-game-name (constantly "the-js")
                       :log-fn noop-log})]
        (is (= 200 (:status response)))
        (is (= "checkout-cycle" (:checkout-id @delivered)))
        (is (= ["order-cycle"] (:order-ids @delivered)))))))

(deftest seller-invoice-replay-without-cycle-ids-fails-closed
  (let [delivered (atom false)
        invoice {:id "in_replayed" :billing_reason "subscription_update"
                 :subscription "sub_seller" :amount_paid 2875 :currency "usd"
                 :lines {:data [{:amount 2875 :price {:id "price_pro"}}]}
                 :subscription_details {:metadata {:game-name "the-js"
                                                  :commerce-adapter "liam"
                                                  :checkout-id "checkout-original"}}}]
    (with-redefs [seller-commerce/plan-sku-for-price! (fn [& _] "pro")]
      (let [response (payments/stripe-subscription-invoice-paid-response
                      {:event-id "evt_replayed" :invoice invoice
                       :submit-service-event
                       (fn [& _] {:body {:gm-result {:success true
                                                      :data {:already-recorded true}}}})
                       :verify-seller-payment (fn [& _] (reset! delivered true))
                       :fallback-game-name (constantly "the-js")
                       :log-fn noop-log})]
        (is (= 500 (:status response)))
        (is (= "seller-subscription-cycle-reference-missing"
               (get-in response [:body :error :details :code])))
        (is (false? @delivered))))))

(deftest paid-plan-change-sends-the-resolved-sku-to-market
  (let [events (atom [])
        invoice {:id "in_upgrade" :billing_reason "subscription_update"
                 :subscription "sub_existing" :amount_paid 1200 :currency "usd"
                 :lines {:data [{:amount -1800 :price {:id "price_plus"}}
                                {:amount 3000 :price {:id "price_pro"}}]}
                 :subscription_details {:metadata {:game-name "the-js"
                                                   :commerce-adapter "liam"
                                                   :buyer "buyer"}}}]
    (with-redefs [seller-commerce/plan-sku-for-price! (fn [_ price]
                                                        (when (= "price_pro" price) "pro"))]
      (payments/stripe-subscription-invoice-paid-response
       {:invoice invoice
        :submit-service-event (fn [_ event _ _ data]
                                (swap! events conj {:event event :data data})
                                {:body {:gm-result {:success true
                                                    :data {:subscription-id "market-sub"
                                                           :checkout-id "cycle-checkout"
                                                           :order-id "cycle-order"}}}})
        :verify-seller-payment (constantly {:ok? true})
        :fallback-game-name (constantly "the-js")
        :log-fn noop-log}))
    (is (= "market/record-subscription-invoice-paid" (:event (first @events))))
    (is (= "pro" (get-in (first @events) [:data :plan-sku])))))

(deftest current-stripe-invoice-shape-resolves-subscription-and-upgrade-price
  (let [events (atom [])
        invoice {:id "in_current" :billing_reason "subscription_update"
                 :amount_paid 1200 :currency "usd"
                 :parent {:subscription_details
                          {:subscription "sub_existing"
                           :metadata {:game-name "the-js" :commerce-adapter "liam"
                                      :buyer "buyer"}}}
                 :lines {:data [{:amount -1800
                                 :pricing {:price_details {:price "price_plus"}}}
                                {:amount 3000
                                 :pricing {:price_details {:price "price_pro"}}}]}}]
    (with-redefs [seller-commerce/plan-sku-for-price! (fn [_ price]
                                                        (when (= "price_pro" price) "pro"))]
      (payments/stripe-subscription-invoice-paid-response
       {:invoice invoice
        :submit-service-event (fn [_ event _ _ data]
                                (swap! events conj {:event event :data data})
                                {:body {:gm-result {:success true
                                                    :data {:subscription-id "market-sub"
                                                           :checkout-id "cycle-checkout"
                                                           :order-id "cycle-order"}}}})
        :verify-seller-payment (constantly {:ok? true})
        :fallback-game-name (constantly "the-js")
        :log-fn noop-log}))
    (is (= "sub_existing" (get-in (first @events)
                                    [:data :provider-subscription-id])))
    (is (= "pro" (get-in (first @events) [:data :plan-sku])))))

(deftest paid-upgrade-is-retried-when-market-has-not-synced-the-new-plan
  (with-redefs [seller-commerce/plan-sku-for-price! (fn [& _] "pro")]
    (let [response (payments/stripe-subscription-invoice-paid-response
                    {:invoice {:id "in_upgrade" :billing_reason "subscription_update"
                               :subscription "sub_existing" :amount_paid 1200
                               :currency "usd"
                               :lines {:data [{:amount 1200 :price {:id "price_pro"}}]}
                               :subscription_details {:metadata {:game-name "the-js"
                                                                  :commerce-adapter "liam"}}}
                     :submit-service-event
                     (fn [& _] {:status 409 :body {:gm-result
                                                  {:success false
                                                   :error {:code "subscription-plan-unresolved"}}}})
                     :fallback-game-name (constantly "the-js")
                     :log-fn noop-log})]
      (is (= 503 (:status response))))))

(deftest an-invoice-with-no-subscription-is-ignored
  (let [[resp events] (renewal-resp {:id "in_x" :billing_reason "subscription_cycle"})]
    (is (= 200 (:status resp)))
    (is (= "ignored" (get-in resp [:body :status])))
    (is (empty? events))))

(deftest a-rejected-renewal-still-answers-200
  ;; A rejection (canceled subscription, unknown id) is terminal for this
  ;; invoice. Non-2xx would make Stripe retry for days against a decision that
  ;; will never change.
  (let [resp (payments/stripe-subscription-invoice-paid-response
               {:invoice {:id "in_003" :billing_reason "subscription_cycle"
                          :subscription "sub_gone"
                          :subscription_details {:metadata {:game-name "the-js"}}}
                :submit-service-event (fn [& _] {:body {:gm-result {:success false}}})
                :fallback-game-name (constantly "the-js")
                :log-fn noop-log})]
    (is (= 200 (:status resp)))
    (is (= "rejected" (get-in resp [:body :status])))))

(deftest a-deleted-subscription-cancels-on-our-side
  (let [events (atom [])
        resp (payments/stripe-subscription-deleted-response
               {:subscription {:id "sub_1" :metadata {:game-name "the-js"}}
                :submit-service-event (fn [_ event-type _ _ data]
                                        (swap! events conj {:type event-type :data data})
                                        {:body {:gm-result {:success true}}})
                :fallback-game-name (constantly "the-js")
                :log-fn noop-log})]
    (is (= 200 (:status resp)))
    (is (= "canceled" (get-in resp [:body :status])))
    (is (= ["market/cancel-subscription"] (mapv :type @events)))
    (is (= "sub_1" (get-in (first @events) [:data :provider-subscription-id])))))

(deftest a-relayed-409-is-an-idempotent-replay-not-a-failure
  ;; MoM strips the GM's error code when relaying a 409, leaving only
  ;; {"error":"handler-call-failed","message":"clj-http: status 409"}. Without
  ;; recognising that, a redelivered checkout.session.completed for an
  ;; already-paid checkout answers 500 and Stripe retries a settled fact for
  ;; days. Seen live the moment the local webhook forwarder started working.
  (with-redefs [coselling-ai.stripe/fetch-payment-intent
                (fn [& _] {:ok? true :payment-intent {:metadata {}}})]
    (let [resp (payments/stripe-session-completed-response
                 {:event-id "evt_replay"
                  :session {:id "cs_1" :payment_intent "pi_1"
                            :amount_total 7999 :currency "usd"
                            :metadata {:checkout-id "checkout-1" :buyer "moon"
                                       :game-name "the-js"}}
                  :submit-service-event
                  (fn [& _] {:body {:gm-result {:success false
                                                :error "handler-call-failed"
                                                :message "clj-http: status 409"}}})
                  :fallback-game-name (constantly "the-js")
                  :log-fn noop-log})]
      (is (= 200 (:status resp)) "a settled fact must never provoke a retry storm")
      (is (= "already-paid" (get-in resp [:body :status]))))))

(deftest shared-platform-webhook-ignores-foreign-and-unscoped-checkouts
  (doseq [game ["the-js" nil]]
    (with-redefs [coselling-ai.stripe/verify-signature (fn [& _] {:ok? true})
                  coselling-ai.stripe/parse-event
                  (fn [_] {:id "evt_other" :type "checkout.session.completed"
                           :data {:object {:payment_status "paid" :metadata {:game-name game}}}})]
      (let [result (payments/handle-stripe-webhook
                     {:raw-body "{}" :signature "sig" :expected-game-name "agents-of-mind"
                      :submit-service-event (fn [& _] (throw (Exception. "must not submit")))
                      :log-fn (fn [& _])})]
        (is (= 200 (:status result)))
        (is (= "another-game" (get-in result [:body :ignored])))))))

(deftest webhook-accepts-either-aom-secret-but-not-another-games-secret
  (doseq [signature ["platform-secret" "connect-secret" "foreign-secret"]]
    (with-redefs [coselling-ai.stripe/verify-signature
                  (fn [_ sig secret] {:ok? (= sig secret) :reason :mismatch})
                  coselling-ai.stripe/parse-event (fn [_] {:type "unhandled.event"})]
      (let [result (payments/handle-stripe-webhook
                     {:raw-body "{}" :signature signature
                      :webhook-secrets ["platform-secret" "connect-secret"]
                      :log-fn (fn [& _])})]
        (is (= (if (= signature "foreign-secret") 400 200) (:status result)))))))
