(ns coselling-ai.editorial
  "The Attention boundary: what makes it safe for an AI assistant, connected
   through MoM's MCP server as a player seat, to run the blog.

   Ported from OVERHEAD (overhead-im `src/overhead/protocols.clj`):

   - Every shared Attention capability requires MoM's verified member caller.
     gm-lib's Attention declares no `:authorization`, so without this the actor
     is whatever `event/data.player-name` says, and anyone holding any seat
     could draft, review or publish as someone else.
   - Blog posts are validated on submit, approve and edit (`blog-content-error`).
   - A successful editorial command is audited, and an MCP `idempotency_key`
     replays its first result, so a retried call never publishes twice.
   - Attention is refused (503) until MoM confirms it is declared AND
     protected, so drafts never reach MoM's public projections."
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [game.gm.authorization :as auth]
            [game.gm.config :as gm-config]
            [game.gm.identity :as gm-identity]
            [game.protocols.attention :as attention]))

;; --- Blog post contract ---
;;
;; Attention content fields, as a blog post reads them. The address is
;; Attention's own feed slug (chosen by the reviewer or derived from the
;; title), permanent once published.

(def ^:private limits {:title 140 :summary 300 :body 50000})

(defn- text? [v] (and (string? v) (not (str/blank? v))))

(defn blog-content-error
  "Why `content` cannot be a blog post, or nil when it can."
  [content]
  (let [{:keys [title summary body featured_image]} content]
    (cond
      (not (map? content)) "Content must be a map with title, summary and body."
      (not (text? title)) "A post needs a title."
      (not (text? summary)) "A post needs a one or two sentence summary; it is the blog listing and the share preview."
      (not (text? body)) "A post needs a Markdown body."
      (> (count title) (:title limits)) (str "Keep the title under " (:title limits) " characters.")
      (> (count summary) (:summary limits)) (str "Keep the summary under " (:summary limits) " characters.")
      (> (count body) (:body limits)) "The body is too long for one post."
      (and (some? featured_image)
           (not (and (string? featured_image)
                     (re-matches #"https://[^\s\"'<>]+" featured_image))))
      "featured_image must be an absolute https:// image URL."
      :else nil)))

(defn- find-task [state id]
  (let [tasks (get-in state [:attention :tasks])]
    (or (get tasks (keyword (str id))) (get tasks (str id)))))

(defn- find-post [state id]
  (first (filter #(= id (:id %)) (get-in state [:attention :feed]))))

(defn- guard-content
  "Validate the post an editorial command would leave behind."
  [op handler]
  (fn [payload]
    (let [state (:game-state payload)
          data (:event/data payload)
          content (case op
                    :submit-draft (:content data)
                    :review-task (when (contains? #{"approve" :approve} (:decision data))
                                   (:draft-content (find-task state (:task-id data))))
                    :update-feed-item (when-let [post (find-post state (:item-id data))]
                                        (merge (:payload post) (dissoc (:updates data) :topics))))
          error (when content (blog-content-error content))]
      (if error
        (auth/error 422 "invalid-post" error)
        (handler payload)))))

;; --- The hardened manifest ---

(def attention-manifest
  {:attention
   (into {}
         (for [[op capability] (:attention attention/manifest)]
           [op (if (and (map? capability) (:handler capability))
                 (cond-> (assoc capability :authorization
                                (or (:authorization capability) {:caller :member}))
                   ;; Task state holds unpublished drafts.
                   (= op :query-tasks) (assoc :roles #{:strategist :creator})
                   (contains? #{:submit-draft :review-task :update-feed-item} op)
                   (update :handler #(guard-content op %)))
                 capability)]))})

;; --- MoM registration gate ---

(defonce registration (atom {:ready false :reason "Waiting for MoM to confirm Attention is protected."}))

(defn ready? [] (true? (:ready @registration)))

(defn refresh-registration!
  [game-name]
  (try
    (let [r (http/get (str (gm-identity/mom-url nil) "/admin/games")
                      {:headers (gm-config/mom-auth-headers) :as :json :throw-exceptions false
                       :connection-timeout 5000 :socket-timeout 10000})
          games (if (vector? (:body r)) (:body r) (get-in r [:body :games]))
          game (first (filter #(= game-name (:game/game-name %)) games))
          ready (and (= 200 (:status r))
                     (contains? (set (:game/protocols game)) "attention")
                     (contains? (set (:game/protected-protocols game)) "attention"))]
      (reset! registration
              (if ready
                {:ready true}
                {:ready false :reason "Protect Attention for this game in Portal before editorial work is allowed."})))
    (catch Exception _
      (reset! registration {:ready false :reason "MoM registration could not be verified; editorial work is paused."}))))

;; --- Audit and idempotent replay ---
;;
;; Kept inside :attention because it holds draft content and actor names, and
;; Attention is the namespace MoM protects. Bounded so state does not grow
;; without limit.

(def ^:private max-audit 500)
(def ^:private max-requests 200)

(defn- fingerprint [v]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (->> (.digest digest (.getBytes (json/generate-string v) "UTF-8"))
         (map #(format "%02x" (bit-and % 0xff)))
         (apply str))))

(defn- prior-request [state request-id]
  (let [requests (get-in state [:attention :editorial-requests])]
    (or (get requests request-id) (get requests (keyword request-id)))))

(defn- remember [attention-state request-id record entry]
  (cond-> (update attention-state :editorial-audit
                  #(vec (take-last max-audit (conj (vec %) entry))))
    request-id
    (update :editorial-requests
            (fn [requests]
              (let [requests (assoc (or requests {}) request-id record)]
                (if (<= (count requests) max-requests)
                  requests
                  (into {} (take-last max-requests (sort-by (comp :at val) requests)))))))))

(defn- editorial-command? [capability event]
  (and (= "attention" (namespace event)) (= :command (:type capability))))

(defn- audited
  [payload manifest dispatch-fn]
  (let [event (some-> (:event/type payload) keyword)
        capability (when event (get-in manifest [(some-> event namespace keyword) (keyword (name event))]))
        editorial? (and event (map? capability) (editorial-command? capability event))
        key (:idempotency-key payload)
        actor (select-keys (:caller payload) [:membership-id :player-name :type :owner-id])
        request-hash (fingerprint {:event (str event) :data (:event/data payload) :actor actor})
        request-id (when (and editorial? (string? key) (not (str/blank? key)))
                     (fingerprint {:actor actor :key key}))
        prior (when request-id (prior-request (:game-state payload) request-id))
        refusal (when (map? capability) (auth/authorize-capability payload capability))]
    (cond
      refusal refusal
      (and prior (not= request-hash (:fingerprint prior)))
      (auth/error 409 "idempotency-conflict" "This request key was already used with different content.")
      prior (:result prior)
      :else
      (let [result (dispatch-fn payload)]
        (if (and editorial? (:success result) (map? (get-in result [:updated-state :attention])))
          (let [data (:event/data payload)
                at (str (java.time.Instant/now))
                entry (cond-> {:operation (str (namespace event) "/" (name event))
                               :actor actor :at at}
                        (:task-id data) (assoc :task-id (:task-id data))
                        (:item-id data) (assoc :item-id (:item-id data))
                        (:decision data) (assoc :decision (str (:decision data)))
                        ;; Chat approval: the assistant records the statement it
                        ;; acted on. An attestation, not proof of a human click.
                        (= "review-task" (name event)) (assoc :approval-statement (:feedback data)))
                record {:fingerprint request-hash :at at
                        :result (select-keys result [:success :data :events])}]
            (update-in result [:updated-state :attention] remember request-id record entry))
          result)))))

;; --- Entry point ---

(defn handle
  "Dispatch `payload` through `dispatch-fn`, applying the Attention boundary."
  [payload manifest dispatch-fn]
  (let [attention-event? (= "attention" (some-> (:event/type payload) keyword namespace))
        payload (cond-> payload
                  ;; The caller's verified roles, never ones the request claims.
                  (and attention-event? (auth/member-caller? payload))
                  (assoc-in [:event/data :roles] (vec (sort (map name (auth/caller-roles payload))))))]
    (cond
      (not attention-event?) (dispatch-fn payload)
      (not (ready?)) (auth/error 503 "attention-registration-pending" (:reason @registration))
      :else (audited payload manifest dispatch-fn))))

(defn privileged?
  "The game owner or MoM itself: callers allowed to read the whole state."
  [payload]
  (or (auth/owner-caller? payload) (auth/system-caller? payload)))
