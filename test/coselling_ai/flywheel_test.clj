(ns coselling-ai.flywheel-test
  (:require [clojure.test :refer :all]
            [coselling-ai.flywheel :as fw]))

(def now 1763078400000) ;; 2025-11-14T00:00Z
(def day fw/day-ms)
(defn ago [days] (- now (* days day)))

(defn- c [model id] (first (filter #(= id (:id %)) (:cs model))))
(defn- sig [cm id] (first (filter #(= id (:id %)) (cons (:headline cm) (:signals cm)))))
(defn- handoff [model id] (first (filter #(= id (:id %)) (:handoffs model))))

(def snapshot
  ;; Shaped as MoM returns it: JSON keywordizes dynamic keys (:ord-1, :ari).
  {:game {:id "g1" :name "Test" :game-name "test"
          :protocols ["attention" "market" "coseller"]}
   :observed-at "2025-11-14T00:00:00Z"
   :participants [{:player-name "ari" :roles ["coseller"]}
                  {:player-name "bo" :roles ["audience"]}
                  {:player-name "cy" :roles ["audience" "buyer"]}
                  {:player-name "di" :roles ["audience"]}]
   :state
   {:attention
    {:feed [{:id "p1" :slug "why-we-share" :payload {:title "Why we share"} :published-at (ago 3)}
            {:id "p2" :slug "old-post" :payload {:title "Old"} :published-at (ago 50)}]
     :votes {:p1 {:bo {:ts (ago 2)} :cy {:ts (ago 2)}}}
     :comments {:p1 [{:id "c1" :author "bo" :created-at (ago 1)}]}
     :calendar [{:id "e1" :title "Launch week" :starts-at (ago 10) :ends-at (ago 3)}
                {:id "e2" :title "Mystery" :date "[redacted-phone]"}
                {:id "e3" :title "Next drive" :date "2025-12-01"}]}
    :market
    {:orders {:ord-1 {:id "ord-1" :buyer "cy" :status "paid" :paid-at (ago 5)
                      :total {:amount 10000 :currency "usd"}}
              :ord-2 {:id "ord-2" :buyer "cy" :status "paid" :paid-at (ago 4)
                      :total {:amount 5000 :currency "usd"}}
              :ord-3 {:id "ord-3" :buyer "di" :status "paid" :paid-at (ago 45)
                      :total {:amount 2000 :currency "usd"}}
              :ord-4 {:id "ord-4" :buyer "bo" :status "pending-payment"
                      :total {:amount 99999 :currency "usd"}}}
     :subscriptions {:sub-1 {:id "sub-1" :buyer "cy" :status "active"
                             :capability-snapshot {:terms {:price {:amount 29900 :currency "usd"}}}}
                     :sub-2 {:id "sub-2" :buyer "di" :status "canceled"}}}
    :coseller
    {:policy {:algorithm "30-days-linear" :window-days 180 :referral-override-bps 500}
     :cosellers {:ari {:id "ari" :registered-at (ago 60) :status "active"}
                 :cy {:id "cy" :registered-at (ago 2) :status "active"}}
     :signups {:ari {:player "ari" :joined-at (ago 60)}
               :bo {:player "bo" :joined-at (ago 8) :referred-by "ari"}
               :cy {:player "cy" :joined-at (ago 7) :referred-by "ari" :became-coseller-at (ago 2)}
               :di {:player "di" :joined-at (ago 46)}}
     :touches [{:coseller "ari" :buyer "bo" :ts (ago 9) :path "/pages/blog/why-we-share/"}
               {:coseller "ari" :buyer "cy" :ts (ago 8) :path "/pages/launch/"}
               {:coseller "ari" :buyer "visitor:x" :ts (ago 6) :last-ts (ago 5) :count 3
                :path "/pages/blog/why-we-share/" :paths ["/pages/blog/why-we-share/"]}
               {:coseller "ari" :buyer "ari" :ts (ago 4) :path "/"}]
     :settlements {:ord-1 {:order-id "ord-1" :status "settled" :distributed 3000
                           :chain ["ari"] :coseller-credits {:ari {:direct 3000}}}}}}})

(deftest commerce-counts-only-paid-orders-in-range
  (let [m (fw/model snapshot now "30d")
        cm (c m "commerce")]
    (is (= 15000 (get-in cm [:headline :value])) "ord-1 + ord-2; ord-3 is outside, ord-4 unpaid")
    (is (= 2000 (get-in cm [:headline :prior])) "the prior 30 days held ord-3")
    (is (= 2 (:value (sig cm "orders"))))
    (is (= 7500 (:value (sig cm "aov"))))
    (is (= 1.0 (:value (sig cm "repeat-share"))) "cy bought twice")
    (is (= 1 (:value (sig cm "subscriptions"))) "a canceled subscription isn't live")
    (is (= 29900 (:value (sig cm "recurring"))))
    (is (= "usd" (:currency m)))))

(deftest coselling-credits-people-not-links
  (let [cm (c (fw/model snapshot now "30d") "coselling")]
    (is (= 1 (get-in cm [:headline :value])) "ari is the only one who shared")
    (is (= 2 (get-in cm [:headline :of])))
    (is (= 5 (:value (sig cm "touches"))) "a visitor's three visits count, the self-touch doesn't")
    (is (= 3 (:value (sig cm "reach"))))
    (is (= 3000 (:value (sig cm "distributed"))))
    (is (= 1.0 (:value (sig cm "attributed-share")))
        "ord-1 settled with ari in the chain; ord-2 falls in ari's 180-day window")
    (is (= 180 (get-in cm [:attribution :window-days])))
    (is (= "ari" (get-in cm [:top-cosellers 0 :coseller])))))

(deftest content-and-community
  (let [m (fw/model snapshot now "30d")
        content (c m "content")
        community (c m "community")]
    (is (= 1 (get-in content [:headline :value])))
    (is (= 3 (:value (sig content "engagement"))) "two votes and a comment")
    (is (= 2 (:value (sig content "content-touches"))))
    (is (= "Why we share" (get-in content [:top-posts 0 :title])))
    (is (= 2 (get-in community [:headline :value])) "bo and cy joined in range")
    (is (= 1.0 (:value (sig community "referred-share"))))
    (is (= 4 (:value (sig community "members"))))
    (is (= 1 (get-in community [:network :max-depth])))
    (is (= 2 (get-in community [:network :referred])))))

(deftest campaigns-read-free-form-calendar-events
  (let [cm (c (fw/model snapshot now "30d") "campaigns")]
    (is (= 1 (get-in cm [:headline :value])) "launch week overlaps; next drive is upcoming")
    (is (= 1 (:value (sig cm "undated"))) "a sanitizer-redacted date makes an event undated")
    (is (= "Next drive" (get-in cm [:upcoming 0 :title])))
    (is (nil? (:value (sig cm "touch-lift")))
        "every share fell inside the campaign: no baseline, so no lift"))
  (let [s (update-in snapshot [:state :coseller :touches] conj
                     {:coseller "ari" :buyer "zed" :ts (ago 20)})
        lift (:value (sig (c (fw/model s now "30d") "campaigns") "touch-lift"))]
    ;; 3 shares over the 7 campaign days vs 1 over the other 23.
    (is (< 9.8 lift 9.9))))

(deftest handoffs-measure-the-loop
  (let [m (fw/model snapshot now "30d")]
    (is (= 0.5 (:rate (handoff m "content->community"))) "bo arrived on a post, cy on the offer")
    (is (= 0.25 (:rate (handoff m "community->commerce"))) "cy is one of four members")
    (is (= 0.5 (:rate (handoff m "commerce->coselling"))) "cy cosells, di doesn't")
    (is (true? (:approx (handoff m "content->community"))))))

(deftest the-bottleneck-is-the-handoff-furthest-below-its-target
  (testing "every measurable handoff is healthy: no bottleneck"
    (is (nil? (:bottleneck (fw/model snapshot now "30d")))))
  (let [paid (fn [id buyer] [(keyword id) {:id id :buyer buyer :status "paid" :paid-at (ago 2)
                                           :total {:amount 100 :currency "usd"}}])
        s (update-in snapshot [:state :market :orders] into
                     [(paid "ord-5" "bo") (paid "ord-6" "zed") (paid "ord-7" "yan")])
        m (fw/model s now "30d")
        b (:bottleneck m)]
    ;; Buyers cy, di, bo, zed, yan: only cy cosells — 20% against a 25% target.
    ;; Members buying (2 of 4) is far above its 10% target, though lower in raw terms
    ;; than some healthy handoffs would be.
    (is (= "commerce->coselling" (:handoff b)))
    (is (= "below-target" (:kind b)))
    (is (= 0.2 (:rate b)))
    (is (= 0.25 (:target b)))
    (is (string? (:move b)))
    (is (not (re-find #"(?i)passive|income|guarantee" (str (:title b) (:move b))))
        "insights never promise income")))

(deftest a-steep-fall-beats-a-low-level
  ;; Prior 30 days: both of two joins arrived on content. This period: 1 of 4.
  (let [s (-> snapshot
              (assoc-in [:state :coseller :signups]
                        {:p1 {:player "p1" :joined-at (ago 40)} :p2 {:player "p2" :joined-at (ago 41)}
                         :n1 {:player "n1" :joined-at (ago 5)} :n2 {:player "n2" :joined-at (ago 6)}
                         :n3 {:player "n3" :joined-at (ago 7)} :n4 {:player "n4" :joined-at (ago 8)}})
              (assoc-in [:state :coseller :touches]
                        [{:coseller "ari" :buyer "p1" :ts (ago 45) :path "/pages/blog/why-we-share/"}
                         {:coseller "ari" :buyer "p2" :ts (ago 45) :path "/pages/blog/why-we-share/"}
                         {:coseller "ari" :buyer "p3" :ts (ago 45) :path "/pages/blog/why-we-share/"}
                         {:coseller "ari" :buyer "n1" :ts (ago 9) :path "/pages/blog/why-we-share/"}
                         {:coseller "ari" :buyer "n2" :ts (ago 9) :path "/pages/launch/"}
                         {:coseller "ari" :buyer "n3" :ts (ago 9) :path "/pages/launch/"}
                         {:coseller "ari" :buyer "n4" :ts (ago 9) :path "/pages/launch/"}])
              (assoc :participants []))
        b (:bottleneck (fw/model s now "30d"))]
    (is (= "content->community" (:handoff b)))
    (is (= "falling" (:kind b)))
    (is (= 0.25 (:rate b)))))

(deftest an-empty-game-points-at-the-first-dormant-c
  (let [m (fw/model {:game {:protocols ["attention" "market" "coseller"]} :state {}} now "30d")]
    (is (= "dormant" (get-in m [:bottleneck :kind])))
    (is (= "content" (get-in m [:bottleneck :c])))
    (is (every? #(nil? (:rate %)) (:handoffs m)))))

(deftest a-game-without-market-shows-commerce-as-not-enabled
  (let [m (fw/model (assoc-in snapshot [:game :protocols] ["attention" "coseller"]) now "30d")]
    (is (false? (:enabled (c m "commerce"))))
    (is (nil? (:headline (c m "commerce"))))
    (is (:disabled (handoff m "community->commerce")))
    (is (not= "community->commerce" (get-in m [:bottleneck :handoff])))))

(deftest ranges
  (testing "all-time has no prior period"
    (let [m (fw/model snapshot now "all")]
      (is (nil? (:prior-window m)))
      (is (= 17000 (get-in (c m "commerce") [:headline :value])))
      (is (nil? (get-in (c m "commerce") [:headline :prior])))))
  (testing "the boundary: an order exactly at the window start is in, at now is out"
    (let [s (assoc-in snapshot [:state :market :orders]
                      {:a {:id "a" :buyer "x" :status "paid" :paid-at (ago 7) :total {:amount 1 :currency "usd"}}
                       :b {:id "b" :buyer "x" :status "paid" :paid-at now :total {:amount 10 :currency "usd"}}})]
      (is (= 1 (get-in (c (fw/model s now "7d") "commerce") [:headline :value])))))
  (testing "an unknown range falls back to 30 days"
    (is (= "30d" (:range (fw/model snapshot now "bogus"))))))

(deftest timestamps-in-seconds-and-iso-are-read
  (is (= 1763078400000 (fw/->ms 1763078400)))
  (is (= 1763078400000 (fw/->ms "2025-11-14T00:00:00Z")))
  (is (= 1763078400000 (fw/->ms "2025-11-14")))
  (is (nil? (fw/->ms "[redacted-phone]"))))
