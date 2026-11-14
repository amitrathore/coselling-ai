(ns coselling-ai.flywheel
  "The 5Cs flywheel for one game: Content → Community → Commerce → Coselling →
   Campaigns → back to Content (mom/docs/The_Fractals_Co_5Cs_Flywheel.md).

   Pure: `model` takes a host snapshot from MoM
   (GET /api/v1/games/<g>/orgchief/snapshot — sanitized state, participants,
   ledger balances), the time now and a range, and returns the dashboard's read
   model. No I/O, so every number on the page is testable from a fixture.

   Each C reports its economic signal for the range, with the prior period
   alongside. The five handoffs between them are rates, and the weakest one is
   the bottleneck the page leads with.

   Reading the snapshot defensively matters:
   - MoM keywordizes dynamic map keys on the JSON round trip (`:orders` arrives
     keyed `:ord-1`), and some collections are maps in one game and vectors in
     another — `entries` takes either.
   - Timestamps are epoch millis in every gm-lib protocol. Calendar events are
     free-form; their dates may be ISO strings, which MoM's sanitizer can
     redact, so an unparseable date makes an event undated, never an error.
   - Money is minor units, `{:amount :currency}` on orders, bare integers on
     settlements (in the order's currency)."
  (:require [clojure.string :as str]))

(def day-ms (* 24 60 60 1000))

(def ranges
  "Selectable ranges, in days. \"all\" has no prior period to compare with."
  {"7d" 7 "30d" 30 "90d" 90 "all" nil})

(def default-range "30d")

;; --- reading the snapshot ---------------------------------------------------

(defn- entries
  "Records of a collection stored either as a map (id → record) or a vector."
  [coll]
  (cond (map? coll) (vals coll)
        (sequential? coll) coll
        :else []))

(defn- kname [k]
  (cond (keyword? k) (name k)
        (nil? k) nil
        :else (str k)))

(defn ->ms
  "Epoch millis from millis, seconds, or an ISO-8601 string; nil otherwise."
  [v]
  (cond
    (and (number? v) (pos? v)) (let [n (long v)] (if (< n 100000000000) (* n 1000) n))
    (string? v) (or (try (.toEpochMilli (java.time.Instant/parse v)) (catch Exception _ nil))
                    (try (-> (java.time.LocalDate/parse v)
                             (.atStartOfDay java.time.ZoneOffset/UTC)
                             .toInstant .toEpochMilli)
                         (catch Exception _ nil)))
    :else nil))

(defn- status [r] (some-> (:status r) kname str/lower-case))

(defn- in-window? [{:keys [start end]} ts]
  (and ts (or (nil? start) (>= ts start)) (< ts end)))

(defn- ratio [n d]
  (when (and n d (pos? d)) (double (/ n d))))

(defn- amount-of [m] (if (map? m) (or (:amount m) 0) (or m 0)))

(defn- currency-of [m] (some-> (when (map? m) (:currency m)) kname str/lower-case))

;; --- the game's raw facts -----------------------------------------------------

(def ^:private unpaid-statuses #{"pending-payment" "cancelled" "canceled" "expired" "failed"})
(def ^:private refunded-statuses #{"refunded" "void-refunded"})

(defn- paid-orders [state]
  (->> (entries (get-in state [:market :orders]))
       (keep (fn [o]
               (let [paid-at (->ms (:paid-at o))]
                 (when (and paid-at (not (unpaid-statuses (status o))))
                   (assoc o ::paid-at paid-at)))))))

(defn- touch-ts [t] (->ms (or (:last-ts t) (:ts t))))
(defn- touch-first-ts [t] (->ms (:ts t)))

(defn- real-touches
  "Touches that can earn: a coseller reaching someone other than themselves."
  [state]
  (->> (entries (get-in state [:coseller :touches]))
       (filter #(and (:coseller %) (:buyer %) (not= (kname (:coseller %)) (kname (:buyer %)))))))

(defn- touch-paths [t]
  (->> (concat [(:path t)] (entries (:paths t)))
       (keep #(some-> % str))
       distinct))

(defn- signups
  "Member signups keyed by player-name. Falls back to MoM participants for
   games whose coseller protocol never recorded them."
  [state participants]
  (let [recorded (->> (get-in state [:coseller :signups])
                      (map (fn [[k v]] [(or (some-> (:player v) kname) (kname k)) v]))
                      (into {}))]
    (reduce (fn [m p]
              (let [n (kname (:player-name p))]
                (if (or (nil? n) (contains? m n))
                  m
                  (assoc m n {:player n :joined-at (:joined-at p)}))))
            recorded
            participants)))

(defn- cosellers [state]
  (->> (get-in state [:coseller :cosellers])
       (map (fn [[k v]] [(or (some-> (:id v) kname) (kname k)) v]))
       (into {})))

(defn- settlements [state]
  (->> (entries (get-in state [:coseller :settlements]))
       (map (fn [s] [(kname (:order-id s)) s]))
       (into {})))

(defn- feed [state] (entries (get-in state [:attention :feed])))

(defn- item-map
  "votes/comments are keyed by feed-item id (keywordized on the way in)."
  [m]
  (into {} (map (fn [[k v]] [(kname k) v])) (if (map? m) m {})))

(defn- event-span
  "[start end) of a calendar event, or nil if it carries no readable date. An
   event with no end counts for a week — a launch's tail."
  [e]
  (when-let [start (some ->ms ((juxt :starts-at :start :start-at :date :at :ts :when) e))]
    (let [end (some ->ms ((juxt :ends-at :end :end-at :until) e))]
      [start (if (and end (> end start)) end (+ start (* 7 day-ms)))])))

(defn- in-any-span? [spans ts]
  (boolean (and ts (some (fn [[s e]] (and (>= ts s) (< ts e))) spans))))

(defn- content-path?
  "Did a touch land on content? The blog, or any published item's slug."
  [slugs path]
  (let [p (str/lower-case (str path))]
    (or (str/includes? p "/blog")
        (some #(str/includes? p (str "/" % "/")) slugs)
        (some #(str/ends-with? p (str "/" %)) slugs))))

;; --- money --------------------------------------------------------------------

(defn- primary-currency
  "The currency most paid orders are in. Headlines show one currency; the rest
   are listed, never summed across."
  [orders]
  (or (->> orders
           (map #(or (currency-of (:total %)) "usd"))
           frequencies
           (sort-by (comp - val))
           ffirst)
      "usd"))

(defn- in-currency [currency orders]
  (filter #(= currency (or (currency-of (:total %)) "usd")) orders))

;; --- one window -------------------------------------------------------------

(defn- facts
  "Everything the five Cs need, computed once for one window."
  [snapshot window currency]
  (let [state (:state snapshot)
        signups (signups state (:participants snapshot))
        cosellers (cosellers state)
        settlements (settlements state)
        orders (paid-orders state)
        orders-cur (in-currency currency orders)
        win-orders (filter #(in-window? window (::paid-at %)) orders-cur)
        touches (real-touches state)
        win-touches (filter #(in-window? window (touch-ts %)) touches)
        feed (feed state)
        slugs (->> feed (keep :slug) (map str) (remove #(< (count %) 3)) set)
        votes (item-map (get-in state [:attention :votes]))
        comments (item-map (get-in state [:attention :comments]))
        calendar (entries (get-in state [:attention :calendar]))
        spans (keep event-span calendar)
        win-spans (filter (fn [[s e]] (and (< s (:end window))
                                           (or (nil? (:start window)) (> e (:start window)))))
                          spans)]
    {:state state :window window :currency currency
     :signups signups :cosellers cosellers :settlements settlements
     :orders orders :orders-cur orders-cur :win-orders win-orders
     :touches touches :win-touches win-touches
     :feed feed :slugs slugs :votes votes :comments comments
     :calendar calendar :spans spans :win-spans win-spans}))

(defn- metric [id label value fmt & [extra]]
  (merge {:id id :label label :value value :format fmt} extra))

;; Content ---------------------------------------------------------------------

(defn- content [{:keys [feed votes comments window slugs win-touches]}]
  (let [published (filter #(in-window? window (->ms (:published-at %))) feed)
        vote-ts (for [[_ by-player] votes [_ v] (if (map? by-player) by-player {})] (->ms (:ts v)))
        comment-ts (for [[_ cs] comments c (entries cs)] (->ms (:created-at c)))
        engagement (count (filter #(in-window? window %) (concat vote-ts comment-ts)))
        content-touches (filter #(some (partial content-path? slugs) (touch-paths %)) win-touches)
        reach-of (fn [item]
                   (let [slug (some-> (:slug item) str)]
                     (if (and slug (>= (count slug) 3))
                       (count (filter #(some (partial content-path? #{slug}) (touch-paths %))
                                      win-touches))
                       0)))
        top (->> feed
                 (map (fn [item]
                        (let [id (kname (:id item))]
                          {:title (or (get-in item [:payload :title]) (:slug item) "Untitled")
                           :slug (:slug item)
                           :published-at (->ms (:published-at item))
                           :votes (count (get votes id))
                           :comments (count (entries (get comments id)))
                           :touches (reach-of item)})))
                 (sort-by (fn [p] (- (+ (:votes p) (:comments p) (* 2 (:touches p))))))
                 (take 5)
                 vec)]
    {:headline (metric "published" "Posts published" (count published) "count")
     :signals [(metric "engagement" "Votes + comments" engagement "count")
               (metric "content-touches" "Shares that landed on content" (count content-touches) "count")
               (metric "library" "Posts in the library" (count feed) "count")]
     :top-posts top}))

;; Community -------------------------------------------------------------------

(defn- recruit-tree
  "Depth and breadth of the referral graph (signups' :referred-by edges)."
  [signups]
  (let [parent (into {} (keep (fn [[p s]] (when-let [r (some-> (:referred-by s) kname)]
                                            (when (not= r p) [p r]))))
                     signups)
        depth (fn [p] (loop [p p d 0 seen #{}]
                        (if-let [r (get parent p)]
                          (if (or (seen r) (> d 64)) d (recur r (inc d) (conj seen p)))
                          d)))
        recruiters (frequencies (vals parent))]
    {:max-depth (reduce max 0 (map depth (keys parent)))
     :recruiters (count recruiters)
     :referred (count parent)
     :recruits-per-recruiter (ratio (count parent) (count recruiters))}))

(defn- community [{:keys [signups window cosellers]}]
  (let [joined (filter (fn [[_ s]] (in-window? window (->ms (:joined-at s)))) signups)
        referred (filter (fn [[_ s]] (:referred-by s)) joined)
        became (filter (fn [[_ s]] (in-window? window (->ms (:became-coseller-at s)))) signups)
        tree (recruit-tree signups)]
    {:headline (metric "joins" "New members" (count joined) "count")
     :signals [(metric "members" "Members" (count signups) "count")
               (metric "referred-share" "Joined through a coseller" (ratio (count referred) (count joined)) "percent")
               (metric "audience-to-coseller" "Members who are cosellers" (ratio (count cosellers) (count signups)) "percent")
               (metric "became-cosellers" "Became cosellers" (count became) "count")]
     :network tree}))

;; Commerce --------------------------------------------------------------------

(defn- live-sub? [s]
  (#{"pending-activation" "active" "past-due" "cancel-at-period-end" "paused"} (status s)))

(defn- commerce [{:keys [state win-orders orders-cur orders settlements window currency]}]
  (let [gmv (reduce + 0 (map (comp amount-of :total) win-orders))
        buyers (frequencies (map (comp kname :buyer) orders-cur))
        win-buyers (set (map (comp kname :buyer) win-orders))
        repeaters (count (filter #(>= (get buyers % 0) 2) win-buyers))
        subs (filter live-sub? (entries (get-in state [:market :subscriptions])))
        recurring (reduce + 0 (keep #(let [p (get-in % [:capability-snapshot :terms :price])]
                                       (when (= currency (or (currency-of p) currency)) (amount-of p)))
                                    subs))
        refunds (count (filter #(and (refunded-statuses (status %))
                                     (in-window? window (->ms (or (:refunded-at %) (:updated-at %)
                                                                  (:paid-at %)))))
                               (entries (get-in state [:market :orders]))))
        win-ids (set (map (comp kname :id) win-orders))
        win-settlements (keep #(get settlements %) win-ids)
        settled (count (filter #(= "settled" (status %)) win-settlements))
        other-currencies (->> orders (map #(or (currency-of (:total %)) "usd")) distinct
                              (remove #{currency}) vec)]
    {:headline (metric "gmv" "Sales" gmv "money")
     :signals [(metric "orders" "Paid orders" (count win-orders) "count")
               (metric "aov" "Average order" (when (seq win-orders) (quot gmv (count win-orders))) "money")
               (metric "repeat-share" "Repeat buyers" (ratio repeaters (count win-buyers)) "percent")
               (metric "subscriptions" "Active subscriptions" (count subs) "count")
               (metric "recurring" "Recurring per cycle" recurring "money")
               (metric "refunds" "Refunds" refunds "count")
               (metric "settled" "Orders settled" settled "count"
                       {:of (count win-settlements)})]
     :other-currencies other-currencies}))

;; Coselling -------------------------------------------------------------------

(defn- coselling [{:keys [win-touches win-orders settlements cosellers signups orders-cur window state]}]
  (let [active (set (map (comp kname :coseller) win-touches))
        reach (->> win-touches (map (comp kname :buyer)) distinct)
        touch-volume (reduce + 0 (map #(max 1 (or (:count %) 1)) win-touches))
        window-days (or (get-in state [:coseller :policy :window-days]) 30)
        touches-all (real-touches state)
        attributed? (fn [o]
                      (if-let [s (get settlements (kname (:id o)))]
                        (or (pos? (amount-of (:distributed s))) (seq (:chain s)))
                        ;; Not settled yet: would a coseller be credited?
                        (let [paid (::paid-at o) from (- paid (* window-days day-ms))]
                          (some #(and (= (kname (:buyer %)) (kname (:buyer o)))
                                      (let [ts (touch-first-ts %)] (and ts (>= ts from) (<= ts paid))))
                                touches-all))))
        attributed (filter attributed? win-orders)
        gmv (reduce + 0 (map (comp amount-of :total) win-orders))
        attributed-gmv (reduce + 0 (map (comp amount-of :total) attributed))
        win-settlements (keep #(get settlements (kname (:id %))) win-orders)
        distributed (reduce + 0 (map (comp amount-of :distributed) win-settlements))
        credits (for [s win-settlements [c {:keys [direct override]}] (:coseller-credits s)]
                  {:coseller (kname c) :direct (amount-of direct) :override (amount-of override)})
        direct (reduce + 0 (map :direct credits))
        override (reduce + 0 (map :override credits))
        chains (keep #(let [n (count (:chain %))] (when (pos? n) n)) win-settlements)
        members (set (keys signups))
        buyers-ever (set (map (comp kname :buyer) orders-cur))
        reached-members (filter members reach)
        reached-buyers (filter buyers-ever reach)
        by-coseller (group-by :coseller credits)
        reach-by (frequencies (map (comp kname :coseller) win-touches))
        top (->> active
                 (map (fn [c] {:coseller c
                               :reach (get reach-by c 0)
                               :earned (reduce + 0 (map #(+ (:direct %) (:override %)) (get by-coseller c)))
                               :override (reduce + 0 (map :override (get by-coseller c)))}))
                 (sort-by (juxt (comp - :earned) (comp - :reach)))
                 (take 5) vec)
        new-cosellers (count (filter #(in-window? window (->ms (:registered-at %))) (vals cosellers)))]
    {:headline (metric "active" "Active cosellers" (count active) "count" {:of (count cosellers)})
     :signals [(metric "activation" "Cosellers who shared" (ratio (count (filter (set (keys cosellers)) active)) (count cosellers)) "percent")
               (metric "new-cosellers" "New cosellers" new-cosellers "count")
               (metric "touches" "Shares landed" touch-volume "count")
               (metric "reach" "People reached" (count reach) "count")
               (metric "attributed-share" "Sales with a coseller in the chain" (ratio attributed-gmv gmv) "percent")
               (metric "distributed" "Shared with cosellers" distributed "money")
               (metric "override-share" "Of that, from recruits" (ratio override (+ direct override)) "percent")
               (metric "chain" "Cosellers per sale" (when (seq chains) (double (/ (reduce + chains) (count chains)))) "number")
               (metric "touch-to-member" "Reached who became members" (ratio (count reached-members) (count reach)) "percent")
               (metric "touch-to-buyer" "Reached who bought" (ratio (count reached-buyers) (count reach)) "percent")]
     :top-cosellers top
     :attribution {:algorithm (get-in state [:coseller :policy :algorithm])
                   :window-days window-days
                   :override-bps (get-in state [:coseller :policy :referral-override-bps])}}))

;; Campaigns -------------------------------------------------------------------

(defn- per-day [n ms] (when (pos? ms) (/ (double n) (/ ms day-ms))))

(defn- lift
  "Rate inside campaign windows over the rate outside them, within `window`."
  [window spans tss]
  (let [start (or (:start window) (reduce min (:end window) (concat (map first spans) tss)))
        end (:end window)
        inside-ms (reduce + 0 (map (fn [[s e]] (max 0 (- (min e end) (max s start)))) spans))
        outside-ms (- (- end start) inside-ms)
        inside (count (filter #(in-any-span? spans %) tss))
        outside (- (count tss) inside)
        rin (per-day inside inside-ms)
        rout (per-day outside outside-ms)]
    (when (and rin rout (pos? rout)) (/ rin rout))))

(defn- campaigns [{:keys [calendar win-spans window win-touches signups]}]
  (let [undated (count (remove event-span calendar))
        touch-ts (keep touch-ts win-touches)
        join-ts (keep (fn [[_ s]] (let [t (->ms (:joined-at s))] (when (in-window? window t) t))) signups)
        upcoming (->> calendar
                      (keep (fn [e] (when-let [[s _] (event-span e)]
                                      (when (>= s (:end window))
                                        {:title (or (:title e) (:name e) "Untitled") :starts-at s}))))
                      (sort-by :starts-at) (take 3) vec)]
    {:headline (metric "campaigns" "Campaigns running" (count win-spans) "count")
     :signals [(metric "touch-lift" "Shares during campaigns vs. other days"
                       (when (seq win-spans) (lift window win-spans touch-ts)) "multiple")
               (metric "join-lift" "Joins during campaigns vs. other days"
                       (when (seq win-spans) (lift window win-spans join-ts)) "multiple")
               (metric "on-calendar" "Events on the calendar" (count calendar) "count")
               (metric "undated" "Events without a date" undated "count")]
     :upcoming upcoming}))

;; --- handoffs ---------------------------------------------------------------

(defn- first-touch-path [touches member]
  (some->> touches
           (filter #(= member (kname (:buyer %))))
           (sort-by #(or (touch-first-ts %) Long/MAX_VALUE))
           first
           :path))

(defn- handoffs
  [{:keys [signups window touches slugs win-orders orders-cur cosellers win-touches spans win-spans feed]}]
  (let [joined (keep (fn [[p s]] (when (in-window? window (->ms (:joined-at s))) p)) signups)
        via-content (filter #(some->> (first-touch-path touches %) (content-path? slugs)) joined)
        members (set (keys signups))
        win-buyers (set (map (comp kname :buyer) win-orders))
        all-buyers (set (map (comp kname :buyer) orders-cur))
        buyer-cosellers (filter (set (keys cosellers)) all-buyers)
        active (set (map (comp kname :coseller) win-touches))
        campaign-active (set (keep #(when (in-any-span? win-spans (touch-ts %)) (kname (:coseller %))) win-touches))
        published (keep #(let [t (->ms (:published-at %))] (when (in-window? window t) t)) feed)
        ;; A campaign's tail: content made in its wake counts for two weeks after.
        wake (map (fn [[s e]] [s (+ e (* 14 day-ms))]) spans)
        post-campaign (filter #(in-any-span? wake %) published)]
    [{:id "content->community" :from "content" :to "community" :approx true
      :label "New members whose first visit was content"
      :rate (ratio (count via-content) (count joined)) :n (count joined)}
     {:id "community->commerce" :from "community" :to "commerce"
      :label "Members who bought"
      :rate (ratio (count (filter members win-buyers)) (count members)) :n (count members)}
     {:id "commerce->coselling" :from "commerce" :to "coselling"
      :label "Buyers who became cosellers"
      :rate (ratio (count buyer-cosellers) (count all-buyers)) :n (count all-buyers)}
     {:id "coselling->campaigns" :from "coselling" :to "campaigns"
      :label "Active cosellers who shared during a campaign"
      :rate (when (seq win-spans) (ratio (count campaign-active) (count active))) :n (count active)}
     {:id "campaigns->content" :from "campaigns" :to "content" :approx true
      :label "Posts published during or after a campaign"
      :rate (when (seq spans) (ratio (count post-campaign) (count published))) :n (count published)}]))

;; --- the insight ------------------------------------------------------------

(def ^:private moves
  "One next move per handoff, in the #noselling voice: share, don't close;
   grow other cosellers. Never an income promise."
  {"content->community"
   {:title "Content isn't turning into members."
    :move "Put a Join invitation on your best posts, and hand cosellers those posts to share. Content is the easiest thing to pass on."}
   "community->commerce"
   {:title "Members aren't buying yet."
    :move "Let members see the offer where they already gather. A member's own story about the product does more than a pitch."}
   "commerce->coselling"
   {:title "Buyers aren't becoming cosellers."
    :move "Invite every buyer to Share & Earn right after they buy. People who bought are the most believable people to share."}
   "coselling->campaigns"
   {:title "Cosellers aren't showing up for campaigns."
    :move "Give each campaign something worth sharing — a post, a story, a moment — and tell cosellers before it starts."}
   "campaigns->content"
   {:title "Campaigns aren't feeding new content."
    :move "Turn each campaign's results and stories into posts. That is what restarts the loop."}})

(def ^:private dormant
  {"content" {:title "There's no content in the loop yet."
              :move "Publish the first post. Content is what cosellers share, so the loop starts here."}
   "community" {:title "No one has joined yet."
                :move "Share the join link with the people closest to the network first."}
   "commerce" {:title "Nothing has sold in this range."
               :move "Check the offer is live, then let cosellers share the content around it — not the checkout."}
   "coselling" {:title "No one has shared in this range."
                :move "Invite your most engaged members to coseller. Anyone can share anything; credit follows the person."}
   "campaigns" {:title "No campaigns on the calendar."
                :move "Put one launch, challenge or seasonal drive on the calendar. Campaigns are where cosellers move together."}})

(def targets
  "A healthy level for each handoff. The rates aren't comparable raw — buying
   is rarer than joining in any network — so each is judged against its own
   target. Starting points, to be tuned against real networks."
  {"content->community" 0.30
   "community->commerce" 0.10
   "commerce->coselling" 0.25
   "coselling->campaigns" 0.50
   "campaigns->content" 0.50})

(defn- bottleneck
  "The weakest handoff: the steepest fall from the prior period (by a third or
   more) if there is one, otherwise the furthest below its target, with enough
   people behind it to mean anything. With no measurable handoff, the first
   dormant C."
  [hs prior-hs cs]
  (let [prior (into {} (map (juxt :id :rate)) prior-hs)
        measurable (filter #(and (:rate %) (>= (:n % 0) 3)) hs)
        falls (->> measurable
                   (keep (fn [h] (when-let [p (prior (:id h))]
                                   (when (and (pos? p) (<= (:rate h) (* p 2/3)))
                                     (assoc h :fall (- 1 (/ (:rate h) p)))))))
                   (sort-by (comp - :fall)))
        health #(/ (:rate %) (get targets (:id %) 1.0))
        below (->> measurable (filter #(< (health %) 1)) (sort-by health))
        pick (or (first falls) (first below))]
    (cond
      pick (merge {:kind (if (:fall pick) "falling" "below-target")
                   :handoff (:id pick) :from (:from pick) :to (:to pick)
                   :rate (:rate pick) :prior (prior (:id pick)) :target (targets (:id pick))}
                  (moves (:id pick)))
      :else (when-let [c (first (filter #(and (:enabled %) (let [v (get-in % [:headline :value])]
                                                             (or (nil? v) (zero? v))))
                                        cs))]
              (merge {:kind "dormant" :c (:id c)} (dormant (:id c)))))))

;; --- the model --------------------------------------------------------------

(def loop-order ["content" "community" "commerce" "coselling" "campaigns"])

(def ^:private c-names
  {"content" "Content" "community" "Community" "commerce" "Commerce"
   "coselling" "Coselling" "campaigns" "Campaigns"})

(def ^:private requires
  "The protocol a C reads from; Community is every game's memberships."
  {"content" "attention" "commerce" "market" "coselling" "coseller" "campaigns" "attention"})

(defn- enabled? [protocols c]
  (let [p (requires c)]
    (or (nil? p) (empty? protocols) (contains? protocols p))))

(defn- with-prior
  "Attach the prior period's value to each metric, by id."
  [current prior]
  (if-not prior
    current
    (let [pv (into {} (map (juxt :id :value)) (cons (:headline prior) (:signals prior)))
          attach #(assoc % :prior (get pv (:id %)))]
      (-> current
          (update :headline attach)
          (update :signals #(mapv attach %))))))

(defn- windows [range-key now]
  (let [days (get ranges range-key)]
    (if days
      (let [len (* days day-ms) start (- now len)]
        [{:start start :end now} {:start (- start len) :end start}])
      [{:start nil :end now} nil])))

(defn model
  "The dashboard read model for one game. `range-key` is a key of `ranges`."
  [snapshot now range-key]
  (let [range-key (if (contains? ranges range-key) range-key default-range)
        [window prior-window] (windows range-key now)
        protocols (set (map str (get-in snapshot [:game :protocols])))
        currency (primary-currency (paid-orders (:state snapshot)))
        f (facts snapshot window currency)
        pf (when prior-window (facts snapshot prior-window currency))
        builders {"content" content "community" community "commerce" commerce
                  "coselling" coselling "campaigns" campaigns}
        cs (mapv (fn [c]
                   (let [on? (enabled? protocols c)]
                     (merge {:id c :name (c-names c) :enabled on?}
                            (when on?
                              (with-prior ((builders c) f) (when pf ((builders c) pf)))))))
                 loop-order)
        hs (handoffs f)
        prior-hs (when pf (handoffs pf))
        prior-rate (into {} (map (juxt :id :rate)) prior-hs)
        on (set (map :id (filter :enabled cs)))
        hs (mapv (fn [h] (cond-> (assoc h :prior (prior-rate (:id h)) :target (targets (:id h)))
                           (not (and (on (:from h)) (on (:to h)))) (assoc :rate nil :disabled true)))
                 hs)]
    {:game (select-keys (:game snapshot) [:id :name :game-name :protocols])
     :observed-at (:observed-at snapshot)
     :range range-key
     :window window
     :prior-window prior-window
     :currency currency
     :cs cs
     :handoffs hs
     :bottleneck (bottleneck (remove :disabled hs) (remove :disabled prior-hs) cs)}))
