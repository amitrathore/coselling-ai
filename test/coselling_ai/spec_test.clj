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
  ;; change Coselling.ai's economics silently. The attribution window is the
  ;; one deliberate departure: 180 days rather than 30.
  (is (= {:algorithm "30-days-linear"
          :pool-share-bps 8000
          :referral-override-bps 500
          :window-days 180
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

(deftest offer-listings-match-the-prices-on-the-offer-page
  ;; The page is hand-edited HTML and the listing is what is charged; nothing
  ;; else ties the two numbers together.
  (let [page (slurp "resources/site/pages/launch-your-network/index.html")
        by-id (into {} (map (juxt :id identity) spec/offer-listings))
        setup (get by-id "launch-your-network-setup")
        monthly (get by-id "network-platform-monthly")]
    (is (= {:amount 499900 :currency "USD"} (get-in setup [:terms :price])))
    (is (nil? (get-in setup [:terms :price :recurring])) "setup is one-time")
    (is (= 29900 (get-in monthly [:terms :price :amount])))
    (is (= {:interval :month} (get-in monthly [:terms :price :recurring])))
    (is (clojure.string/includes? page "$4,999"))
    (is (clojure.string/includes? page "$299"))
    (is (= 4000 (:coseller-commission-bps setup)))
    (is (= 3500 (:coseller-commission-bps monthly)))
    (is (every? #(= ["networks"] (:category-slugs %)) spec/offer-listings)
        "the category the market seed registers")))


(deftest the-coseller-guide-matches-the-economics
  ;; The guide is general; its "On Coselling.ai" box prints this site's own
  ;; figures as hand-written HTML. The coseller share is the listing's
  ;; commission times the policy's pool share, before the card processing
  ;; fee. A rate or policy change must update the page too.
  (let [page (slurp "resources/site/pages/coseller-guide/index.html")
        policy (get-in spec/game-spec [:initial-state :coseller :policy])
        by-id (into {} (map (juxt :id identity) spec/offer-listings))
        share-bps (fn [listing] (quot (* (:coseller-commission-bps listing) (:pool-share-bps policy)) 10000))
        pct (fn [bps] (str "up to " (quot bps 100) "%"))
        dollars (fn [listing] (Math/round (/ (* (get-in listing [:terms :price :amount]) (share-bps listing)) 1000000.0)))
        setup (get by-id "launch-your-network-setup")
        monthly (get by-id "network-platform-monthly")]
    (is (clojure.string/includes? page (pct (share-bps setup))))
    (is (clojure.string/includes? page (pct (share-bps monthly))))
    (is (clojure.string/includes? page (format "about $%,d" (dollars setup))))
    (is (clojure.string/includes? page (format "about $%d)" (dollars monthly))))
    (is (clojure.string/includes? page "$4,999"))
    (is (clojure.string/includes? page "$299"))
    (is (clojure.string/includes? page (str "keeps " (- 100 (quot (:referral-override-bps policy) 100)) "%")))
    (is (clojure.string/includes? page (str (:window-days policy) " days")))
    (is (clojure.string/includes? page (str (:payout-hold-days policy) "-day hold")))))

(deftest the-supporter-badge-is-a-one-dollar-player-entitlement
  (let [badge spec/badge-listing
        seeded (set (map :slug (get-in spec/game-spec [:market-seed :hashtags])))]
    (is (= {:amount 100 :currency "USD"} (get-in badge [:terms :price])))
    (is (nil? (get-in badge [:terms :price :recurring])) "one-time")
    (is (= 3000 (:coseller-commission-bps badge)))
    (is (set/subset? (set (:hashtag-slugs badge)) seeded)
        "listings.clj throws on a slug the seed never registered")
    (is (= {:type :account-entitlement :recipient {:type :player}}
           (get-in badge [:commerce :fulfillment])))
    (is (not (contains? (set (map :id spec/offer-listings)) (:id badge)))
        "kept out of the offer listings")
    (is (= "/pages/share/" (get spec/return-path-by-capability (:id badge))))))
