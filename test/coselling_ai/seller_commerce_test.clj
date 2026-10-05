(ns coselling-ai.seller-commerce-test
  (:require [coselling-ai.seller-commerce :as seller-commerce]
            [clojure.test :refer [deftest is]]))

(deftest stripe-facts-become-provider-neutral-lifecycle-event
  (let [event (seller-commerce/payment->lifecycle-event
               {:commerce-adapter "liam"
                :checkout-id "checkout-1"
                :checkout-batch-id "batch-1"
                :market-subscription-id "subscription-checkout-1"
                :buyer "buyer"
                :fulfillment-ref "seller:checkout-1"
                :payment-provider "stripe"
                :payment-mode "test"
                :payment-status "paid"
                :stripe-event-id "evt-1"
                :occurred-at "2026-09-29T12:00:00Z"
                :stripe-session-id "cs-1"
                :stripe-payment-intent-id "pi-1"
                :amount-total 4900
                :application-fee-amount 1470
                :currency "usd"})]
    (is (= "payment.confirmed" (:event_type event)))
    (is (= "evt-1" (:event_id event)))
    (is (= "2026-09-29T12:00:00Z" (:occurred_at event)))
    (is (= "subscription-checkout-1" (get-in event [:market :subscription_id])))
    (is (= {:amount 4900 :currency "USD"} (get-in event [:payment :money])))
    (is (= "pi-1" (get-in event [:payment :provider_references :payment_intent])))
    (is (not-any? #(re-find #"stripe" (name %)) (keys event)))))

(deftest refund-notification-identity-is-stable-across-stripe-events
  (let [payment {:event-kind "refund" :checkout-id "checkout-1"
                 :stripe-refund-id "re_1" :stripe-payment-intent-id "pi_1"
                 :amount 1000 :occurred-at "2026-09-30T20:00:00Z"}
        created (seller-commerce/payment->lifecycle-event
                 (assoc payment :stripe-event-id "evt_created"))
        updated (seller-commerce/payment->lifecycle-event
                 (assoc payment :stripe-event-id "evt_updated"))]
    (is (= "refund.settled" (:event_type created)))
    (is (= "re_1" (:event_id created)))
    (is (= created updated))))

(deftest renewal-and-dispute-have-distinct-types
  (let [renewal (seller-commerce/payment->lifecycle-event
                 {:billing-reason "subscription_cycle"
                  :checkout-id "checkout-cycle" :order-ids ["order-cycle"]
                  :market-subscription-id "subscription-stable"
                  :stripe-invoice-id "in-1"})]
    (is (= "subscription.renewed" (:event_type renewal)))
    (is (= ["order-cycle"] (get-in renewal [:market :order_ids])))
    (is (= "subscription-stable" (get-in renewal [:market :subscription_id]))))
  (is (= "chargeback.resolved"
         (:event_type (seller-commerce/payment->lifecycle-event
                       {:event-kind "chargeback" :outcome "won"
                        :stripe-dispute-id "dp-1"})))))

(deftest free-activation-is-a-provider-neutral-confirmation
  (let [event (seller-commerce/payment->lifecycle-event
               {:checkout-id "checkout-free" :checkout-batch-id "batch-free"
                :buyer "buyer" :fulfillment-ref "seller:checkout-free"
                :plan-sku "pre" :payment-provider "none" :payment-mode "none"
                :payment-status "free" :amount-total 0 :currency "USD"})]
    (is (= "payment.confirmed" (:event_type event)))
    (is (= "none" (get-in event [:payment :provider])))
    (is (= "none" (get-in event [:payment :mode])))
    (is (= {:amount 0 :currency "USD"} (get-in event [:payment :money])))
    (is (= "pre" (get-in event [:payload :plan_sku])))))
