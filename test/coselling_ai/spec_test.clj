(ns coselling-ai.spec-test
  "Guards the spec decisions that fail silently rather than loudly."
  (:require [clojure.test :refer :all]
            [clojure.set :as set]
            [coselling-ai.spec :as spec]
            [coselling-ai.handlers :as handlers]))

(deftest same-protocols-as-the-js
  (is (= ["attention" "market" "coseller" "withdrawal" "introspection" "avatar"]
         (:protocols spec/game-spec))))

(deftest every-advertised-protocol-has-handlers
  ;; An advertised protocol with no handlers does not fail at boot — it fails
  ;; later, inside a player's request, as an obscure "no handler found".
  (doseq [p (:protocols spec/game-spec)]
    (is (contains? handlers/protocol-registry p)
        (str "protocol '" p "' is advertised to MoM but has no manifest")))
  (is (seq (:gm handlers/merged-manifest))
      "the gm/ lifecycle handlers must survive the merge"))

(deftest market-and-coseller-are-registered-whole
  (let [ops (fn [k] (set (keys (get handlers/merged-manifest k))))]
    (is (contains? (ops :coseller) :settle-checkout))
    (is (contains? (ops :market) :declare-capability) "merchants must be able to list")
    (is (contains? (ops :market) :mark-checkout-paid) "market must be functional")
    (is (contains? (ops :market) :mark-fulfilled))))

(deftest player-slots-speak-the-protocols-vocabulary
  ;; A seat named "operator" validates in MoM and is then refused by every
  ;; attention op, which gates on :strategist. Display words live in the UI.
  (let [roles (set (map :role (:player-slots spec/game-spec)))]
    (is (= #{"strategist" "creator" "contributor" "audience"
             "buyer" "seller" "coseller" "observer"}
           roles))
    (is (empty? (set/intersection roles #{"operator" "merchant" "resident" "member"})))
    (is (= 1 (:min (first (filter #(= "strategist" (:role %))
                                  (:player-slots spec/game-spec)))))
        "someone must run the network")))

(deftest merchants-join-by-invitation
  (is (= ["audience" "coseller"] (:self-claimable-roles spec/game-spec)))
  (is (not-any? #{"seller" "strategist"} (:self-claimable-roles spec/game-spec))))

(deftest coseller-policy-is-the-js-policy-stated-explicitly
  ;; Intergraph policy for every game, including the recursive 5% recruiter
  ;; share, as on Hey108. Stated explicitly so gm-lib default drift can't
  ;; change Coselling.ai's economics silently.
  (is (= {:algorithm "30-days-linear"
          :pool-share-bps 8000
          :referral-override-bps 500
          :window-days 30
          :payout-hold-days 30}
         (dissoc (get-in spec/game-spec [:initial-state :coseller :policy]) :history))))

(deftest the-return-window-never-outlasts-the-payout-hold
  ;; Settlement empties @buyer-escrow; a refund after that is :blocked-manual
  ;; with no ledger effect. gm-lib validates neither number against the other.
  (let [hold (get-in spec/game-spec [:initial-state :coseller :policy :payout-hold-days])
        window (get-in spec/game-spec [:initial-state :market :returns-policy :return-window-days])]
    (is (<= window hold))))

(deftest market-seed-is-coselling-ais-own
  ;; Without it gm-lib seeds The J's band taxonomy (Merch, Music, #drop).
  (let [slugs (set (map :slug (get-in spec/game-spec [:market-seed :categories])))]
    (is (= #{"networks" "apps"} slugs))
    (is (empty? (set/intersection slugs #{"merch" "music" "collabs"})))))

(deftest brand-bible-is-a-seed
  (is (= #{:name :mission} (set (keys spec/brand-bible))))
  (is (= "Conversions through conversations." (:mission spec/brand-bible))))

(deftest public-state-hides-coseller-economics
  (let [effective (assoc-in (:initial-state spec/game-spec)
                            [:coseller :touches] [{:coseller "ari" :ts 1}])
        public (handlers/public-game-state effective)]
    (is (= #{:attention :market} (set (keys public))))
    (is (nil? (:coseller public)))))

(deftest overlay-fills-only-missing-state
  (is (= {:a 1 :b {:c 2 :d 3}}
         (handlers/overlay-state {:a 0 :b {:c 0 :d 3}} {:a 1 :b {:c 2}})))
  (is (= {:a 0} (handlers/overlay-state {:a 0} nil))))
