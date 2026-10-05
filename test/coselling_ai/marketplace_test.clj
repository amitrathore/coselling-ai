(ns coselling-ai.marketplace-test
  (:require [clojure.test :refer :all]
            [coselling-ai.marketplace :as market]
            [coselling-ai.handlers :as handlers]
            [coselling-ai.payments :as payments]
            [coselling-ai.seller-commerce :as seller-commerce]
            [coselling-ai.stripe :as stripe]
            [game.gm.config :as config]
            [game.gm.identity :as identity]))

(deftest active-subscription-family-is-detected-before-second-checkout
  (let [cap {:id "pro" :seller "liam-seller"
             :commerce {:subscription-group "liam-credits"}}
        sub {:id "subscription-1" :seller "liam-seller"
             :subscription-group "liam-credits" :status :active}]
    (is (= sub (#'market/active-subscription-for-order
                [sub] {:capability-id "pro" :seller "liam-seller"} [cap])))
    (is (nil? (#'market/active-subscription-for-order
               [(assoc sub :status :canceled)]
               {:capability-id "pro" :seller "liam-seller"} [cap])))))

(deftest paid-upgrade-targets-the-existing-subscription-item
  (let [portal-args (atom nil)
        cap {:id "pro" :seller "liam-seller" :status :active
             :terms {:price {:amount 7900}}
             :commerce {:subscription-group "liam-credits" :price-ref "price_pro"}}
        sub {:id "subscription-1" :seller "liam-seller" :status :active
             :commerce-adapter "liam" :provider-subscription-id "sub_existing"
             :subscription-group "liam-credits"
             :capability-snapshot {:terms {:price {:amount 4900}}}}]
    (with-redefs [identity/require-selected-seat
                  (fn [& _] {:context {:game-name "agents-of-mind"}
                             :seat {:player-name "buyer"}})
                  market/submit-service-event
                  (fn [_ event & _]
                    {:status 200 :body {:gm-result {:success true
                                                   :data (case event
                                                           "market/query-subscriptions"
                                                           {:subscriptions [sub]}
                                                           "market/query-capabilities"
                                                           {:capabilities [cap]})}}})
                  market/resolve-seller-connect-accounts
                  (fn [& _] {:accounts {"liam-seller" {:stripe-account-id "acct_seller"}}})
                  seller-commerce/adapter (constantly {:id "liam"})
                  seller-commerce/stripe-account-id (fn [& _] "acct_seller")
                  stripe/retrieve-subscription
                  (fn [_] {:ok? true :subscription {:customer "cus_buyer"
                                                    :items {:data [{:id "si_existing"}]}}})
                  stripe/create-customer-portal-session
                  (fn [args] (reset! portal-args args)
                    {:ok? true :url "https://billing.stripe.com/test"})]
      (let [response (market/routes
                      {:request-method :post
                       :uri "/api/market/subscriptions/subscription-1/portal"
                       :body {:target-capability-id "pro"}})]
        (is (= 200 (:status response)))
        (is (= "sub_existing" (:subscription-id @portal-args)))
        (is (= "si_existing" (:subscription-item-id @portal-args)))
        (is (= "price_pro" (:target-price-id @portal-args)))))))

(deftest checkout-fails-closed-without-payment-configuration
  (with-redefs [market/payments-ready? (constantly false)]
    (is (= 503 (:status (handlers/app {:request-method :post :uri "/api/market/checkout-session" :headers {}}))))))

(deftest free-seller-session-reaches-its-handler-without-stripe
  (with-redefs [market/payments-ready? (constantly false)
                identity/require-selected-seat (fn [& _] {:response {:status 401 :body {}}})]
    (is (= 401 (:status (handlers/app
                         {:request-method :post
                          :uri "/api/market/checkout-batches/batch/checkouts/checkout/session"
                          :headers {}}))))))

(deftest paid-seller-session-stays-blocked-without-stripe
  (let [events (atom [])]
    (with-redefs [market/payments-ready? (constantly false)
                  identity/require-selected-seat
                  (fn [& _] {:context {:game-name "agents-of-mind"}
                             :seat {:player-name "buyer"}})
                  seller-commerce/adapter (constantly {:adapter "liam"})
                  market/submit-service-event
                  (fn [_ event & _]
                    (swap! events conj event)
                    {:status 200 :body {:gm-result {:success true :data
                      (case event
                        "market/query-checkout-batches"
                        {:checkout-batches [{:id "batch" :buyer "buyer"
                                             :checkouts ["checkout"]}]}
                        "market/query-checkouts"
                        {:checkouts [{:id "checkout" :status "pending-payment"
                                      :funds-flow "seller-direct" :commerce-adapter "liam"
                                      :total {:amount 4900 :currency "USD"}}]})}}})]
      (let [response (market/routes
                      {:request-method :post
                       :uri "/api/market/checkout-batches/batch/checkouts/checkout/session"
                       :body {:recipient-email "buyer@example.com" :attempt-id "attempt"}})]
        (is (= 503 (:status response)))
        (is (= "payments-unavailable" (get-in response [:body :error :code])))
        (is (= ["market/query-checkout-batches" "market/query-checkouts"] @events))))))

(deftest unauthenticated-checkout-is-rejected
  (with-redefs [identity/require-selected-seat (fn [& _] {:response {:status 401 :body {}}})]
    (doseq [path ["/api/market/purchases" "/api/market/checkout-session"
                  "/api/market/checkout-batches/batch/checkouts/checkout/session"]]
      (is (= 401 (:status (market/routes {:request-method (if (= path "/api/market/purchases") :get :post)
                                        :uri path :headers {} :body {}})))))))

(deftest seller-direct-refund-uses-the-orders-connected-charge
  (let [events (atom [])
        stripe-call (atom nil)
        lookup-call (atom nil)]
    (with-redefs [identity/require-selected-seat
                  (fn [& _] {:context {:game-name "agents-of-mind"}
                             :seat {:player-name "liam-seller"
                                    :player-roles ["seller"]}})
                  market/submit-service-event
                  (fn [_ event actor _ data]
                    (swap! events conj [event actor data])
                    {:status 200
                     :body {:gm-result {:success true
                                        :data (case event
                                                "market/query-orders"
                                                {:orders [{:id "order-1" :seller "liam-seller"
                                                           :checkout-id "checkout-1"}]}
                                                "market/query-checkouts"
                                                {:checkouts [{:id "checkout-1"
                                                              :funds-flow :seller-direct
                                                              :total {:amount 4900}
                                                              :payment-ref {:session-id "cs_1"
                                                                            :connected-account "acct_seller"}}]}
                                                "market/issue-refund" {:status :reserved}
                                                "market/mark-refund-settled" {:status :refunded})}}})
                  config/resolve-stripe-secret-key (constantly "sk_test")
                  stripe/resolve-checkout-payment-intent
                  (fn [args] (reset! lookup-call args)
                    {:ok? true :payment-intent-id "pi_1"})
                  payments/create-refund
                  (fn [args]
                    (reset! stripe-call args)
                    (swap! events conj ["stripe/refund"])
                    {:ok? true :id "re_1" :status "succeeded"})]
      (let [response (market/routes {:request-method :post :uri "/api/market/refund"
                                     :body {:order-id "order-1" :return-id "return-1"
                                            :amount 1000}})]
        (is (= 200 (:status response)))
        (is (= "refunded" (get-in response [:body :status])))
        (is (= ["market/query-orders" "market/query-checkouts"
                "market/issue-refund" "stripe/refund" "market/mark-refund-settled"]
               (mapv first @events)))
        (is (= "liam-seller" (second (nth @events 2))))
        (is (= "return-1" (get-in @events [2 2 :return-id])))
        (is (= "acct_seller" (:connected-account-id @stripe-call)))
        (is (= "pi_1" (:payment-intent-id @stripe-call)))
        (is (= "cs_1" (:session-id @lookup-call)))
        (is (= 4900 (:amount @lookup-call)))
        (is (= (get-in @events [2 2 :refund-id]) (:refund-id @stripe-call)))))))

(deftest seller-direct-refund-rejects-another-sellers-order-before-stripe
  (let [stripe-calls (atom 0)]
    (with-redefs [identity/require-selected-seat
                  (fn [& _] {:context {:game-name "agents-of-mind"}
                             :seat {:player-name "other-seller"
                                    :player-roles ["seller"]}})
                  market/submit-service-event
                  (fn [_ event & _]
                    {:status 200
                     :body {:gm-result {:success true
                                        :data (when (= event "market/query-orders")
                                                {:orders [{:id "order-1" :seller "liam-seller"
                                                           :checkout-id "checkout-1"}]})}}})
                  payments/create-refund (fn [_] (swap! stripe-calls inc))]
      (let [response (market/routes {:request-method :post :uri "/api/market/refund"
                                     :body {:order-id "order-1" :amount 1000}})]
        (is (= 403 (:status response)))
        (is (zero? @stripe-calls))))))

(deftest uncertain-processor-response-keeps-refund-reserved-for-webhook-recovery
  (let [events (atom [])]
    (with-redefs [identity/require-selected-seat
                  (fn [& _] {:context {:game-name "agents-of-mind"}
                             :seat {:player-name "liam-seller"
                                    :player-roles ["seller"]}})
                  market/submit-service-event
                  (fn [_ event & _]
                    (swap! events conj event)
                    {:status 200
                     :body {:gm-result {:success true
                                        :data (case event
                                                "market/query-orders"
                                                {:orders [{:id "order-1" :seller "liam-seller"
                                                           :checkout-id "checkout-1"}]}
                                                "market/query-checkouts"
                                                {:checkouts [{:id "checkout-1"
                                                              :funds-flow :seller-direct
                                                              :payment-ref {:payment-intent-id "pi_1"
                                                                            :connected-account "acct_seller"}}]}
                                                "market/issue-refund" {:status :reserved})}}})
                  payments/create-refund (fn [_] {:ok? false :status 500})]
      (let [response (market/routes {:request-method :post :uri "/api/market/refund"
                                     :body {:order-id "order-1" :amount 1000}})]
        (is (= 503 (:status response)))
        (is (= "reserved" (get-in response [:body :status])))
        (is (not (some #{"market/reverse-refund"} @events)))))))

(deftest checkout-ownership-checked-before-payment
  (let [calls (atom 0)]
    (with-redefs [identity/require-selected-seat (fn [& _] {:context {:game-name "agents-of-mind"} :seat {:player-name "buyer"}})
                  market/submit-service-event (fn [_ event actor _ _]
                                                (is (= "buyer" actor))
                                                {:status 200 :body {:gm-result {:success true :data {:checkouts [] :checkout-batches []}}}})
                  payments/create-checkout-session (fn [& _] (swap! calls inc))]
      (is (= 404 (:status (market/routes {:request-method :post :uri "/api/market/checkout-session"
                                        :body {:checkout-id "someone-elses"}}))))
      (is (= 404 (:status (market/routes {:request-method :post :uri "/api/market/checkout-batches/foreign/checkouts/foreign/session"
                                        :body {:recipient-email "buyer@example.com" :attempt-id "attempt"}}))))
      (is (zero? @calls)))))

(deftest signed-webhook-body-preserved
  (let [raw "{\"a\": 1}\n" captured (atom nil)
        handler (market/wrap-stripe-raw-body #(do (reset! captured %) {:status 200}))]
    (handler {:request-method :post :uri "/webhooks/stripe"
              :body (java.io.ByteArrayInputStream. (.getBytes raw "UTF-8"))})
    (is (= raw (:stripe/raw-body @captured)))
    (is (= raw (slurp (:body @captured))))))

(deftest native-checkout-rejects-incomplete-snapshots-before-charging
  (let [calls (atom 0)]
    (with-redefs [identity/require-selected-seat (fn [& _] {:context {:game-name "agents-of-mind"} :seat {:player-name "buyer"}})
                  market/submit-service-event
                  (fn [_ event & _]
                    {:status 200 :body {:gm-result {:success true :data
                      (case event
                        "market/query-checkouts" {:checkouts [{:id "checkout" :status "pending-payment"
                          :orders ["missing-order"] :total {:amount 500 :currency "USD"}}]}
                        "market/query-orders" {:orders []}
                        "market/query-capabilities" {:capabilities []})}}})
                  payments/create-checkout-session (fn [& _] (swap! calls inc))]
      (let [response (market/routes {:request-method :post :uri "/api/market/checkout-session"
                                     :body {:checkout-id "checkout"}})]
        (is (= 409 (:status response)))
        (is (= "checkout-total-mismatch" (get-in response [:body :error :code])))
        (is (zero? @calls))))))

(deftest native-checkout-validates-quantity-and-pass-through-totals
  (let [checkout {:orders ["order"] :total {:amount 1100 :currency "USD"}}
        orders [{:id "order"}]
        lines [{:amount 500 :quantity 2 :currency "usd"}
               {:amount 100 :quantity 1 :currency "USD"}]]
    (is (#'market/checkout-lines-match? checkout orders lines))
    (is (not (#'market/checkout-lines-match? checkout orders (rest lines))))
    (is (not (#'market/checkout-lines-match? checkout [] lines)))
    (is (not (#'market/checkout-lines-match? checkout orders (assoc-in lines [0 :currency] "EUR"))))))

(deftest readiness-needs-the-platform-webhook-secret-but-not-connect
  (with-redefs [payments/active-provider-id (constantly "stripe")
                config/resolve-stripe-publishable-key (constantly "pk_test_x")
                market/connect-webhook-secret (constantly nil)]
    (with-redefs [config/resolve-stripe-webhook-secret (constantly "whsec_x")]
      (is (true? (market/payments-ready?))))
    (with-redefs [config/resolve-stripe-webhook-secret (constantly nil)]
      (is (false? (market/payments-ready?))))))

(deftest an-unconfigured-deployment-is-not-ready
  ;; active-provider-id falls back to "stub" when no Stripe key is set. That
  ;; fallback must not read as "payments work".
  (with-redefs [payments/active-provider-id (constantly "stub")
                market/stub-provider-requested? (constantly false)]
    (is (false? (market/payments-ready?))))
  (with-redefs [payments/active-provider-id (constantly "stub")
                market/stub-provider-requested? (constantly true)]
    (is (true? (market/payments-ready?)))))

