(ns coselling-ai.stripe-test
  (:require [clojure.test :refer :all]
            [coselling-ai.stripe :as stripe]
            [clj-http.client :as http])
  (:import (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)
           (java.nio.charset StandardCharsets)))

(defn- hmac-hex [secret payload]
  (let [mac (Mac/getInstance "HmacSHA256")
        spec (SecretKeySpec. (.getBytes ^String secret StandardCharsets/UTF_8) "HmacSHA256")
        _ (.init mac spec)
        bytes (.doFinal mac (.getBytes ^String payload StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) bytes))))

(deftest signature-accepts-valid
  (let [secret "whsec_test"
        raw "{\"hello\":\"world\"}"
        ts 1700000000
        sig (hmac-hex secret (str ts "." raw))
        header (str "t=" ts ",v1=" sig)
        result (stripe/verify-signature raw header secret ts)]
    (is (:ok? result))))

(deftest signature-rejects-tampered-body
  (let [secret "whsec_test"
        ts 1700000000
        sig (hmac-hex secret (str ts ".{\"hello\":\"world\"}"))
        header (str "t=" ts ",v1=" sig)
        result (stripe/verify-signature "{\"hello\":\"evil\"}" header secret ts)]
    (is (not (:ok? result)))
    (is (= :mismatch (:reason result)))))

(deftest signature-rejects-stale-timestamp
  (let [secret "whsec_test"
        raw "{}"
        ts 1700000000
        sig (hmac-hex secret (str ts "." raw))
        header (str "t=" ts ",v1=" sig)
        ;; 10 minutes after the signature ts → outside tolerance
        result (stripe/verify-signature raw header secret (+ ts 600))]
    (is (not (:ok? result)))
    (is (= :expired (:reason result)))))

(deftest signature-rejects-malformed-header
  (let [result (stripe/verify-signature "{}" "garbage" "whsec_test" 1700000000)]
    (is (not (:ok? result)))
    (is (= :malformed (:reason result)))))

(deftest signature-rejects-missing-inputs
  (is (= :missing (:reason (stripe/verify-signature "" "t=1,v1=x" "secret" 1))))
  (is (= :missing (:reason (stripe/verify-signature "body" "" "secret" 1))))
  (is (= :missing (:reason (stripe/verify-signature "body" "t=1,v1=x" "" 1)))))

(deftest signature-accepts-when-multiple-v1-values
  (let [secret "whsec_test"
        raw "{\"a\":1}"
        ts 1700000000
        good (hmac-hex secret (str ts "." raw))
        header (str "t=" ts ",v1=deadbeef,v1=" good)
        result (stripe/verify-signature raw header secret ts)]
    (is (:ok? result))))

(deftest connected-payment-intent-fetch-targets-the-seller-account
  (let [captured (atom nil)]
    (with-redefs [http/get (fn [_ opts]
                             (reset! captured opts)
                             {:status 200 :body {:id "pi_direct"}})]
      (is (:ok? (stripe/fetch-payment-intent
                 {:secret-key "sk_platform"
                  :payment-intent-id "pi_direct"
                  :connected-account-id "acct_seller"})))
      (is (= "acct_seller" (get-in @captured [:headers "Stripe-Account"]))))))

(deftest subscription-checkout-refund-resolves-one-paid-invoice-payment
  (let [calls (atom [])
        args {:secret-key "sk_test" :session-id "cs_1"
              :connected-account-id "acct_seller"
              :checkout-id "checkout-1" :game-name "agents-of-mind"
              :amount 4900}]
    (with-redefs [http/get
                  (fn [url opts]
                    (swap! calls conj [url opts])
                    (if (clojure.string/includes? url "/checkout/sessions/")
                      {:status 200 :body {:id "cs_1" :mode "subscription" :payment_status "paid"
                                          :amount_total 4900 :invoice "in_1"
                                          :metadata {:checkout-id "checkout-1"
                                                     :game-name "agents-of-mind"}}}
                      {:status 200 :body {:has_more false
                                          :data [{:invoice "in_1" :status "paid"
                                                  :amount_paid 4900
                                                  :payment {:type "payment_intent"
                                                            :payment_intent "pi_1"}}]}}))]
      (is (= "pi_1" (:payment-intent-id
                      (stripe/resolve-checkout-payment-intent args))))
      (is (= "in_1" (get-in @calls [1 1 :query-params "invoice"])))
      (is (every? #(= "acct_seller" (get-in % [1 :headers "Stripe-Account"]))
                  @calls)))
    (with-redefs [http/get
                  (fn [url _]
                    (if (clojure.string/includes? url "/checkout/sessions/")
                      {:status 200 :body {:id "cs_1" :mode "subscription" :payment_status "paid"
                                          :amount_total 4900 :invoice "in_1"
                                          :metadata {:checkout-id "another-checkout"
                                                     :game-name "agents-of-mind"}}}
                      (throw (Exception. "Invoice API must not be called"))))]
      (is (= :checkout-payment-mismatch
             (:reason (stripe/resolve-checkout-payment-intent args)))))))

(deftest subscription-invoice-resolves-from-connected-payment-intent
  (let [calls (atom [])]
    (with-redefs [http/get (fn [url opts]
                             (swap! calls conj [url opts])
                             (cond
                               (clojure.string/ends-with? url "/v1/invoice_payments")
                               {:status 200 :body {:data [{:invoice "in_1"}]}}

                               (clojure.string/ends-with? url "/v1/invoices/in_1")
                               {:status 200 :body {:id "in_1"
                                                   :parent {:subscription_details
                                                            {:metadata {:checkout-id "checkout-1"}}}}}))]
      (let [result (stripe/fetch-invoice-for-payment-intent
                    {:secret-key "sk_test" :payment-intent-id "pi_1"
                     :connected-account-id "acct_seller"})]
        (is (:ok? result))
        (is (= "checkout-1"
               (get-in result [:invoice :parent :subscription_details :metadata :checkout-id])))
        (is (= "pi_1" (get-in @calls [0 1 :query-params "payment[payment_intent]"])))
        (is (every? #(= "acct_seller" (get-in % [1 :headers "Stripe-Account"]))
                    @calls))))
  (with-redefs [http/get (fn [_ _] {:status 200 :body {:data [{:invoice "in_a"}
                                                               {:invoice "in_b"}]}})]
    (is (= :ambiguous-invoice-payment
           (:reason (stripe/fetch-invoice-for-payment-intent
                     {:secret-key "sk_test" :payment-intent-id "pi_1"
                      :connected-account-id "acct_seller"})))))))

(deftest charge-refunds-fetch-targets-connected-account-and-refuses-truncation
  (let [captured (atom nil)]
    (with-redefs [http/get (fn [url opts]
                             (reset! captured [url opts])
                             {:status 200 :body {:data [{:id "re_1"}]
                                                 :has_more false}})]
      (is (= ["re_1"]
             (mapv :id (:refunds (stripe/fetch-charge-refunds
                                  {:secret-key "sk_test" :charge-id "ch_1"
                                   :connected-account-id "acct_seller"})))))
      (is (clojure.string/ends-with? (first @captured) "/v1/charges/ch_1/refunds"))
      (is (= "acct_seller" (get-in @captured [1 :headers "Stripe-Account"]))))
  (with-redefs [http/get (fn [_ _] {:status 200 :body {:data [] :has_more true}})]
    (is (= :refund-list-truncated
           (:reason (stripe/fetch-charge-refunds
                     {:secret-key "sk_test" :charge-id "ch_1"})))))))

;; --- Connect wrapper fns ---

(deftest standard-oauth-url-and-code-exchange
  (let [url (stripe/oauth-authorize-url
             {:client-id "ca_123" :redirect-uri "http://localhost/callback?a=1"
              :state "attempt.signature"})
        captured (atom nil)]
    (is (clojure.string/includes? url "scope=read_write"))
    (is (clojure.string/includes? url "client_id=ca_123"))
    (is (clojure.string/includes? url "redirect_uri=http%3A%2F%2Flocalhost%2Fcallback%3Fa%3D1"))
    (with-redefs [http/post (fn [endpoint opts]
                              (reset! captured {:endpoint endpoint :opts opts})
                              {:status 200 :body {:stripe_user_id "acct_standard"}})]
      (is (= {:ok? true :account-id "acct_standard"}
             (stripe/exchange-oauth-code {:secret-key "sk_test" :code "ac_123"})))
      (is (clojure.string/ends-with? (:endpoint @captured) "/oauth/token"))
      (is (= "authorization_code"
             (get-in @captured [:opts :form-params "grant_type"]))))))

(deftest create-connected-account-stamps-metadata-and-returns-id
  (let [captured (atom nil)]
    (with-redefs [http/post (fn [url opts] (reset! captured {:url url :opts opts})
                              {:status 200 :body {:id "acct_new"}})]
      (let [result (stripe/create-connected-account
                    {:secret-key "sk_test" :owner-id "owner-1" :game-name "the-js"
                     :email "p@example.com"})
            params (get-in @captured [:opts :form-params])]
        (is (:ok? result))
        (is (= "acct_new" (:id result)))
        (is (clojure.string/ends-with? (:url @captured) "/v1/accounts"))
        (is (= "express" (get params "type")))
        (is (= "true" (get params "capabilities[transfers][requested]")))
        (is (= "owner-1" (get params "metadata[owner-id]")))
        (is (= "the-js" (get params "metadata[game-name]")))
        (is (= "p@example.com" (get params "email")))))))

(deftest create-connected-account-house-stamps-account-metadata
  (let [captured (atom nil)]
    (with-redefs [http/post (fn [url opts] (reset! captured {:url url :opts opts})
                              {:status 200 :body {:id "acct_ig"}})]
      (let [result (stripe/create-connected-account
                    {:secret-key "sk_test" :house-account "@intergraph"})
            params (get-in @captured [:opts :form-params])]
        (is (:ok? result))
        (is (= "acct_ig" (:id result)))
        (is (= "express" (get params "type")))
        (is (= "@intergraph" (get params "metadata[account]")))
        ;; house accounts carry NO owner/game metadata
        (is (nil? (get params "metadata[owner-id]")))
        (is (nil? (get params "metadata[game-name]")))))))

(deftest create-connected-account-surfaces-error
  (with-redefs [http/post (fn [_ _] {:status 400 :body {:error {:message "bad"}}})]
    (let [result (stripe/create-connected-account {:secret-key "sk" :owner-id "o" :game-name "g"})]
      (is (not (:ok? result)))
      (is (= 400 (:status result))))))

(deftest create-account-link-returns-url
  (let [captured (atom nil)]
    (with-redefs [http/post (fn [url opts] (reset! captured {:url url :opts opts})
                              {:status 200 :body {:url "https://connect.stripe.com/setup/x"}})]
      (let [result (stripe/create-account-link
                    {:secret-key "sk" :account-id "acct_1"
                     :refresh-url "https://app/refresh" :return-url "https://app/return"})
            params (get-in @captured [:opts :form-params])]
        (is (:ok? result))
        (is (= "https://connect.stripe.com/setup/x" (:url result)))
        (is (= "acct_1" (get params "account")))
        (is (= "account_onboarding" (get params "type")))
        (is (= "https://app/refresh" (get params "refresh_url")))
        (is (= "https://app/return" (get params "return_url")))))))

(deftest checkout-session-sets-on-behalf-of-when-given
  (let [captured (atom nil)]
    (with-redefs [http/post (fn [url opts] (reset! captured {:url url :opts opts})
                              {:status 200 :body {:id "cs_1" :url "https://pay/x"}})]
      (let [result (stripe/create-checkout-session
                    {:secret-key "sk" :checkout-id "c1" :buyer "b1" :game-name "the-js"
                     :line-items [{:title "Item" :amount 9900 :currency "usd" :quantity 1}]
                     :success-url "https://s" :cancel-url "https://c"
                     :on-behalf-of "acct_game"})
            params (get-in @captured [:opts :form-params])]
        (is (:ok? result))
        ;; The game is named merchant-of-record on the PaymentIntent.
        (is (= "acct_game" (get params "payment_intent_data[on_behalf_of]")))
        ;; Still separate charges & transfers: no destination/application_fee.
        (is (nil? (get params "payment_intent_data[transfer_data][destination]")))
        (is (nil? (get params "payment_intent_data[application_fee_amount]")))))))

;; on_behalf_of is MANDATORY at this boundary. The route already refuses to proceed
;; without one, but that is a single-caller guarantee; this makes it structural, so
;; no future caller can create a charge the platform is merchant of record for.

(deftest checkout-session-refuses-a-blank-on-behalf-of-without-calling-stripe
  (doseq [obo [nil "" "   "]]
    (let [called (atom false)]
      (with-redefs [http/post (fn [& _] (reset! called true)
                                {:status 200 :body {:id "cs_1" :url "https://pay/x"}})]
        (let [result (stripe/create-checkout-session
                      {:secret-key "sk" :checkout-id "c1" :buyer "b1" :game-name "the-js"
                       :line-items [{:title "Item" :amount 9900 :currency "usd" :quantity 1}]
                       :success-url "https://s" :cancel-url "https://c"
                       :on-behalf-of obo})]
          (is (false? (:ok? result)) (str "for on-behalf-of " (pr-str obo)))
          (is (= "merchant-account-required" (:error-code result)))
          ;; The point of the guard: no session exists to be paid.
          (is (false? @called) "must not reach Stripe without a merchant account"))))))

(deftest checkout-session-stamps-the-merchant-account-into-metadata
  (let [captured (atom nil)]
    (with-redefs [http/post (fn [url opts] (reset! captured {:url url :opts opts})
                              {:status 200 :body {:id "cs_1" :url "https://pay/x"}})]
      (let [result (stripe/create-checkout-session
                    {:secret-key "sk" :checkout-id "c1" :buyer "b1" :game-name "the-js"
                     :line-items [{:title "Item" :amount 9900 :currency "usd" :quantity 1}]
                     :success-url "https://s" :cancel-url "https://c"
                     :on-behalf-of "acct_game"})
            params (get-in @captured [:opts :form-params])]
        (is (:ok? result))
        ;; Stamped on both objects: the webhook sees the PaymentIntent, and the
        ;; session carries it for reconciliation. Without a stamp there is no way
        ;; to tell a legacy pre-gate session from one that lost its attribution.
        (is (= "acct_game" (get params "metadata[merchant-account-id]")))
        (is (= "acct_game" (get params "payment_intent_data[metadata][merchant-account-id]")))))))

(deftest seller-checkout-is-an-embedded-direct-charge-with-an-application-fee
  (let [captured (atom nil)]
    (with-redefs [http/post (fn [url opts]
                              (reset! captured {:url url :opts opts})
                              {:status 200 :body {:id "cs_direct"
                                                  :client_secret "secret"
                                                  :expires_at 123}})]
      (let [result (stripe/create-embedded-direct-checkout-session
                    {:secret-key "sk_platform" :connected-account-id "acct_yvatar"
                     :checkout-id "checkout-y" :checkout-batch-id "batch-1"
                     :buyer "buyer" :game-name "the-js" :commerce-adapter "yvatar"
                     :fulfillment-ref "fulfill-1"
                     :line-items [{:title "Credits" :amount 1000 :currency "USD" :quantity 1}]
                     :application-fee-amount 350 :return-url "https://game/return"
                     :idempotency-key "attempt-1"})
            opts (:opts @captured)
            params (:form-params opts)]
        (is (:ok? result))
        (is (= "secret" (:client-secret result)))
        (is (= "acct_yvatar" (get-in opts [:headers "Stripe-Account"])))
        (is (= "attempt-1" (get-in opts [:headers "Idempotency-Key"])))
        (is (= "embedded_page" (get params "ui_mode")))
        (is (= "card" (get params "payment_method_types[0]")))
        (is (= "350" (get params "payment_intent_data[application_fee_amount]")))
        (is (= "yvatar" (get params "metadata[commerce-adapter]")))))))

(deftest seller-subscription-uses-price-ref-and-percent-application-fee
  (let [captured (atom nil)]
    (with-redefs [http/post (fn [_ opts]
                              (reset! captured opts)
                              {:status 200 :body {:id "cs_sub" :client_secret "secret"}})]
      (let [result (stripe/create-embedded-direct-checkout-session
                    {:secret-key "sk" :connected-account-id "acct_liam"
                     :checkout-id "checkout-plus" :checkout-batch-id "batch-1"
                     :buyer "buyer" :game-name "agents-of-mind"
                     :commerce-adapter "liam" :fulfillment-ref "fulfill-plus"
                     :market-subscription-id "subscription_free"
                     :line-items [{:title "Liam Plus" :amount 4900 :currency "USD"
                                   :quantity 1 :price-ref "price_plus"
                                   :recurring {:interval "month"}}]
                     :application-fee-bps 3000 :return-url "https://game/return"})
            params (:form-params @captured)]
        (is (:ok? result))
        (is (= "subscription" (get params "mode")))
        (is (= "price_plus" (get params "line_items[0][price]")))
        (is (= "30.0" (get params "subscription_data[application_fee_percent]")))
        (is (= "liam" (get params "subscription_data[metadata][commerce-adapter]")))
        (is (= "subscription_free"
               (get params "subscription_data[metadata][market-subscription-id]")))))))

(deftest retrieve-account-reads-payouts-enabled
  (with-redefs [http/get (fn [url _]
                           (is (clojure.string/ends-with? url "/v1/accounts/acct_9"))
                           {:status 200 :body {:id "acct_9" :payouts_enabled true}})]
    (let [result (stripe/retrieve-account {:secret-key "sk" :account-id "acct_9"})]
      (is (:ok? result))
      (is (true? (get-in result [:account :payouts_enabled]))))))

(deftest create-transfer-sends-idempotency-key-and-destination
  (let [captured (atom nil)]
    (with-redefs [http/post (fn [url opts] (reset! captured {:url url :opts opts})
                              {:status 200 :body {:id "tr_123"}})]
      (let [result (stripe/create-transfer
                    {:secret-key "sk" :amount 2000 :currency "USD"
                     :destination "acct_dest" :withdrawal-id "wd-1"
                     :game-name "the-js" :player-name "alice"})
            params (get-in @captured [:opts :form-params])
            headers (get-in @captured [:opts :headers])]
        (is (:ok? result))
        (is (= "tr_123" (:id result)))
        (is (clojure.string/ends-with? (:url @captured) "/v1/transfers"))
        (is (= "2000" (get params "amount")))
        (is (= "usd" (get params "currency")))
        (is (= "acct_dest" (get params "destination")))
        (is (= "wd-1" (get params "metadata[withdrawal-id]")))
        (is (= "wd-1" (get headers "Idempotency-Key")))))))

(deftest create-transfer-surfaces-balance-insufficient
  (with-redefs [http/post (fn [_ _] {:status 402 :body {:error {:code "balance_insufficient"}}})]
    (let [result (stripe/create-transfer
                  {:secret-key "sk" :amount 999999 :destination "acct_dest"
                   :withdrawal-id "wd-2"})]
      (is (not (:ok? result)))
      (is (= "balance_insufficient" (:error-code result))))))

(deftest available-on-is-read-from-balance-transaction
  (is (= 1700001234
         (stripe/payment-intent-available-on
          {:latest_charge {:balance_transaction {:available_on 1700001234 :fee 30}}})))
  (is (nil? (stripe/payment-intent-available-on {:latest_charge {}}))))

;; --- Refunds ---

(deftest application-fee-refund-is-separate-and-idempotent
  (let [calls (atom [])]
    (with-redefs [stripe/fetch-payment-intent
                  (fn [opts]
                    (swap! calls conj [:payment opts])
                    {:ok? true
                     :payment-intent {:latest_charge {:application_fee "fee_1"}}})
                  http/post
                  (fn [url opts]
                    (swap! calls conj [:post url opts])
                    {:status 200 :body {:id "fr_1" :amount 450}})]
      (let [result (stripe/refund-application-fee
                    {:secret-key "sk_test" :payment-intent-id "pi_1"
                     :connected-account-id "acct_seller"
                     :amount 450 :refund-id "refund-1"})
            [_ url opts] (second @calls)]
        (is (= {:ok? true :id "fr_1" :amount 450} result))
        (is (= "acct_seller" (get-in @calls [0 1 :connected-account-id])))
        (is (= "https://api.stripe.com/v1/application_fees/fee_1/refunds" url))
        (is (= "fee-refund:refund-1" (get-in opts [:headers "Idempotency-Key"])))
        (is (= "450" (get-in opts [:form-params "amount"])))))))

(deftest create-refund-posts-amount-metadata-and-idempotency-key
  (let [captured (atom nil)]
    (with-redefs [http/post (fn [url opts]
                              (reset! captured {:url url :opts opts})
                              {:status 200 :body {:id "re_1" :status "succeeded"}})]
      (let [result (stripe/create-refund {:secret-key "sk_test"
                                          :payment-intent-id "pi_1"
                                          :amount 2500
                                          :refund-id "refund-9f2c"
                                          :order-id "order-1"
                                          :checkout-id "checkout-1"
                                          :game-name "the-js"})
            {:keys [url opts]} @captured
            params (:form-params opts)]
        (is (:ok? result))
        (is (= "re_1" (:id result)))
        (is (= "https://api.stripe.com/v1/refunds" url))
        ;; the refund-id is the processor idempotency key, which is what makes a
        ;; retried refund safe
        (is (= "refund-9f2c" (get-in opts [:headers "Idempotency-Key"])))
        (is (= "pi_1" (get params "payment_intent")))
        (is (= "2500" (get params "amount")))
        ;; and it is stamped in metadata so charge.refunded correlates back
        (is (= "refund-9f2c" (get params "metadata[refund-id]")))
        (is (= "order-1" (get params "metadata[order-id]")))
        (is (= "checkout-1" (get params "metadata[checkout-id]")))
        (is (= "the-js" (get params "metadata[game-name]")))
        ;; Model B: platform-key refund, no destination-charge params
        (is (nil? (get params "refund_application_fee")))
        (is (nil? (get params "reverse_transfer")))))))

(deftest create-refund-omits-amount-for-a-full-refund
  (let [captured (atom nil)]
    (with-redefs [http/post (fn [_ opts] (reset! captured opts)
                              {:status 200 :body {:id "re_2"}})]
      (stripe/create-refund {:secret-key "sk_test" :payment-intent-id "pi_1"
                             :refund-id "r2"})
      (is (nil? (get (:form-params @captured) "amount"))))))

(deftest create-refund-only-sends-stripe-enum-reasons
  (let [captured (atom nil)
        params-for (fn [reason]
                     (with-redefs [http/post (fn [_ opts] (reset! captured opts)
                                               {:status 200 :body {:id "re_3"}})]
                       (stripe/create-refund {:secret-key "sk" :payment-intent-id "pi_1"
                                              :refund-id "r3" :reason reason})
                       (:form-params @captured)))]
    (let [p (params-for "requested_by_customer")]
      (is (= "requested_by_customer" (get p "reason")))
      (is (nil? (get p "metadata[reason]"))))
    (let [p (params-for "arrived damaged")]
      (is (nil? (get p "reason")) "a free-text reason must not be rejected by Stripe")
      (is (= "arrived damaged" (get p "metadata[reason]"))))))

(deftest create-refund-surfaces-the-stripe-error-code
  (with-redefs [http/post (fn [_ _] {:status 402
                                     :body {:error {:code "balance_insufficient"
                                                    :message "Insufficient funds"}}})]
    (let [result (stripe/create-refund {:secret-key "sk" :payment-intent-id "pi_1"
                                        :refund-id "r4" :amount 100})]
      (is (false? (:ok? result)))
      (is (= 402 (:status result)))
      (is (= "balance_insufficient" (:error-code result))))))

(deftest fetch-refund-reads-a-refund-back
  (with-redefs [http/get (fn [url _] (is (= "https://api.stripe.com/v1/refunds/re_1" url))
                           {:status 200 :body {:id "re_1" :status "succeeded"}})]
    (let [result (stripe/fetch-refund {:secret-key "sk" :refund-id "re_1"})]
      (is (:ok? result))
      (is (= "succeeded" (get-in result [:refund :status]))))))

;; --- Checkout Session expiry + enumeration (hard-merchant-gate §6.1) ---
;;
;; The rollout drains pre-gate sessions: sessions created before the merchant
;; gate shipped are still open and, if completed after it, arrive with no
;; merchant-account-id stamp. Expiring them narrows that window. Neither
;; capability existed before.

(deftest expire-checkout-session-posts-to-the-expire-endpoint
  (let [captured (atom nil)]
    (with-redefs [http/post (fn [url opts] (reset! captured {:url url :opts opts})
                              {:status 200 :body {:id "cs_1" :status "expired"}})]
      (let [result (stripe/expire-checkout-session {:secret-key "sk" :session-id "cs_1"})]
        (is (:ok? result))
        (is (= "expired" (:status result)))
        (is (clojure.string/ends-with? (:url @captured) "/v1/checkout/sessions/cs_1/expire"))))))

(deftest expire-checkout-session-requires-args
  (doseq [args [{:secret-key "" :session-id "cs_1"}
                {:secret-key "sk" :session-id ""}
                {:secret-key "sk" :session-id nil}]]
    (let [called (atom false)]
      (with-redefs [http/post (fn [& _] (reset! called true) {:status 200 :body {}})]
        (let [result (stripe/expire-checkout-session args)]
          (is (false? (:ok? result)))
          (is (false? @called) "must not call Stripe with missing args"))))))

(deftest expire-checkout-session-surfaces-a-stripe-rejection
  (with-redefs [http/post (fn [& _] {:status 400 :body {:error {:message "already expired"}}})]
    (let [result (stripe/expire-checkout-session {:secret-key "sk" :session-id "cs_1"})]
      (is (false? (:ok? result)))
      (is (= 400 (:status result))))))

(deftest list-open-checkout-sessions-filters-by-status
  (let [captured (atom nil)]
    (with-redefs [http/get (fn [url opts] (reset! captured {:url url :opts opts})
                             {:status 200 :body {:data [{:id "cs_1"} {:id "cs_2"}]
                                                 :has_more false}})]
      (let [result (stripe/list-open-checkout-sessions {:secret-key "sk"})]
        (is (:ok? result))
        (is (= ["cs_1" "cs_2"] (mapv :id (:sessions result))))
        (is (= "open" (get-in @captured [:opts :query-params "status"]))
            "only open sessions can be expired")
        (is (false? (:has-more? result)))))))

(deftest list-open-checkout-sessions-supports-paging
  (let [captured (atom nil)]
    (with-redefs [http/get (fn [url opts] (reset! captured {:url url :opts opts})
                             {:status 200 :body {:data [{:id "cs_9"}] :has_more true}})]
      (let [result (stripe/list-open-checkout-sessions
                    {:secret-key "sk" :starting-after "cs_8" :limit 1})]
        (is (:ok? result))
        (is (true? (:has-more? result)) "caller must know there is another page to drain")
        (is (= "cs_8" (get-in @captured [:opts :query-params "starting_after"])))
        (is (= "1" (get-in @captured [:opts :query-params "limit"])))))))

(deftest native-checkout-retries-share-an-idempotency-key
  (let [requests (atom [])
        checkout {:secret-key "sk_test" :checkout-id "checkout-1" :buyer "buyer"
                  :game-name "agents-of-mind" :on-behalf-of "acct_aom"
                  :line-items [{:title "Tool" :amount 500 :currency "USD" :quantity 1}]
                  :success-url "https://aom.test/market/cart" :cancel-url "https://aom.test/market/cart"}]
    (with-redefs [http/post (fn [_ opts] (swap! requests conj opts)
                            {:status 200 :body {:id "cs_test" :url "https://checkout.stripe.com/test"}})]
      (stripe/create-checkout-session checkout)
      (stripe/create-checkout-session checkout)
      (is (= ["coselling-checkout:agents-of-mind:checkout-1" "coselling-checkout:agents-of-mind:checkout-1"]
             (mapv #(get-in % [:headers "Idempotency-Key"]) @requests))))))
