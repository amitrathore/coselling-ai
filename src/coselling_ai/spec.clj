(ns coselling-ai.spec
  "Game Specification — the single source of truth for Coselling.ai's identity,
   active protocols, player slots and seed state.

   The :protocols list does double duty: it decides which handlers are
   dispatchable (see handlers/protocol-registry) and what is advertised to MoM at
   game creation.

   :initial-state is NOT applied by MoM. MoM stores it on the game record and
   never reads it back; durable state starts empty and is written only as this
   GM processes events. The seed is overlaid by handlers/overlay-state on the UI
   read path only. Protocol handlers receive MoM's durable state unseeded and
   carry their own defaults — which is why every policy number below that has a
   gm-lib default is stated explicitly anyway.")

;; --- Market taxonomy ---
;;
;; Must be declared: gm-lib's market seeder otherwise imposes THE JS's band
;; taxonomy (Merch, Music, Collabs, #drop). See game.gm.market-seed/seed-market!.
;;
;; Coselling.ai's own market sells the means to run a coseller network: the
;; Launch Your Network offer first, apps later. Hashtags are the site's four
;; audiences plus the operators the offer is pitched to, so a coseller can
;; find what fits the people they know.

(def market-seed
  {:categories [{:slug "networks" :label "Coseller networks" :icon "globe"}
                {:slug "apps"     :label "Apps"              :icon "grid"}]
   :hashtags [{:slug "brands"      :label "#brands"}
              {:slug "creators"    :label "#creators"}
              {:slug "communities" :label "#communities"}
              {:slug "publishers"  :label "#publishers"}
              {:slug "operators"   :label "#operators"}]
   ;; Offers transact through the market's own checkout once the payments
   ;; boundary is ported from agents-of-mind.
   :commerce-adapters []})

;; --- Brand bible ---
;;
;; The seed, not the canon: {:name :mission}, the-js's shape. The mission is
;; the site's own headline.

(def brand-bible
  {:name "Coselling.ai"
   :mission "Conversions through conversations."})

;; --- Game spec ---

(def game-spec
  {:name "Coselling.ai"
   :description (str "Performance-paid distribution networks: grow through the people "
                     "who already influence your customers, and reward them when "
                     "commerce happens.")

   ;; The-js's full protocol set, verbatim. `withdrawal` because a coseller
   ;; network that cannot pay anyone out is a half-protocol.
   :protocols ["attention" "market" "coseller" "withdrawal" "introspection" "avatar"]

   ;; Visitors join as audience and may opt into Coselling under the same
   ;; handle. Market buy ops are role :any, so `buyer` need not be claimable.
   ;; Coselling.ai itself is the seller, by invitation, never self-claimed.
   :self-claimable-roles ["audience" "coseller"]

   :market-seed market-seed

   ;; Role names are the protocols' interface, not display words. attention
   ;; gates its campaign ops on :strategist, drafting on :creator/:contributor,
   ;; comment/upvote on :audience; market gates listing on :seller; coseller
   ;; registration on :coseller. A seat named "operator" would validate in MoM
   ;; and then be refused by every op.
   :player-slots [{:role "strategist"  :min 1 :max nil}  ; runs the network
                  {:role "creator"     :min 0 :max nil}
                  {:role "contributor" :min 0 :max nil}  ; guest content, own byline
                  {:role "audience"    :min 0 :max nil}  ; day-one seat
                  {:role "buyer"       :min 0 :max nil}
                  {:role "seller"      :min 0 :max nil}  ; Coselling.ai, by invitation
                  {:role "coseller"    :min 0 :max nil}
                  {:role "observer"    :min 0 :max nil}]

   :initial-state
   {:attention
    {:brand-bible brand-bible
     :roster {}
     :tasks {}
     :archived-tasks {}
     :calendar []
     :feed []
     :votes {}
     :comments {}
     :research {:status nil :draft-content nil :history []}}

    :market
    {:capabilities {}
     :commerce-adapters {}
     :intents {}
     :offers {}
     :carts {}
     :orders {}
     :disputes {}
     :trust {}
     :categories {}
     :hashtags {}
     ;; One decision split across two protocols; gm-lib validates neither
     ;; against the other. The return window must never outlast the coseller
     ;; payout hold: once cosellers settle, the order's gross has left
     ;; @buyer-escrow and a refund lands as :blocked-manual with no ledger
     ;; effect. Both are gm-lib's defaults (30), stated so neither drifts alone.
     :returns-policy {:return-window-days 30
                      :return-response-days 5
                      :post-denial-grace-days 0}
     :audit-log []}

    :coseller
    {:cosellers {}
     :signups {}
     :touches []
     ;; The J's policy, stated explicitly rather than left to gm-lib's
     ;; default-policy merge.
     ;;
     ;; :referral-override-bps 500 is Intergraph policy for every game: each
     ;; coseller keeps 95% of a sale earning and 5% goes to their own recruiter;
     ;; every recruiter applies the same rule, so a shrinking share continues up
     ;; the chain (residual to @game).
     :policy {:algorithm "30-days-linear"
              :pool-share-bps 8000
              :referral-override-bps 500
              :window-days 30
              :payout-hold-days 30
              :history []}
     :settlements {}
     :payouts {}
     :audit-log []}

    ;; No :withdrawal seed on purpose: withdrawal-state builds its own
    ;; :records map, and seeding the wrong shape is worse than seeding nothing.

    :avatar
    {:avatars {}
     :audit-log []}}})
