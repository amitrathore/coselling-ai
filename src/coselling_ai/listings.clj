(ns coselling-ai.listings
  "One-off operator tool: create the seller player and declare the offer
   listings from `spec/offer-listings`.

     lein run -m coselling-ai.listings

   Reads the same environment as the GM (MOM_URL, MOM_GAME_TOKEN, GAME_NAME)
   plus SELLER_OWNER_ID, the MoM user who owns the seller. Safe to rerun: an
   existing seller is reused and an existing listing is updated in place.

   The seller is a player, not the GM. `seller` is not self-claimable, so a human
   cannot take the seat by signing in; the operator path is an agent slot owned
   by SELLER_OWNER_ID that claims the named player. That user then holds the
   seller seat alongside any handle of their own."
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [game.gm.config :as gm-config]
            [game.gm.identity :as gm-identity]
            [coselling-ai.spec :as spec]))

(def seller-name "coselling")

(defn- env [k] (some-> (System/getenv k) str/trim not-empty))

(defn- mom [method path & [body]]
  (http/request
   (gm-identity/with-auth
     (cond-> {:method method
              :url (str (gm-identity/mom-url nil) path)
              :as :json :coerce :always :throw-exceptions false}
       body (assoc :content-type :json :body (json/generate-string body))))))

(defn- ok? [resp] (<= 200 (long (or (:status resp) 0)) 299))

(defn- fail! [what resp]
  (throw (ex-info (str what " failed") {:status (:status resp) :body (:body resp)})))

(defn- seller-event
  "Submit a Market event as the seller. Returns the GM's result, or throws."
  [game-name event-type data]
  (let [resp (gm-identity/submit-service-event game-name event-type seller-name ["seller"] data)
        result (get-in resp [:body :gm-result])]
    (when-not (and (ok? resp) (:success result))
      (fail! event-type resp))
    result))

(defn- ensure-seller-player!
  "The seller's agent slot is its own (owner, group) pair. Registering is
   destructive for that pair, so an existing slot is looked up first and the
   group is never the GM's."
  [game-name owner-id group-id]
  (let [members (get-in (mom :get (str "/admin/games/" game-name "/memberships"))
                        [:body :memberships])]
    (if (some #(= seller-name (:membership/player-name %)) members)
      (println "seller player exists:" seller-name)
      (let [slot (mom :get (str "/admin/agents/by-slot?owner-id=" owner-id
                                "&player-group-id=" group-id))
            agent-id (or (when (ok? slot) (get-in slot [:body :agent/id]))
                         (let [reg (mom :post "/admin/agents/register"
                                        {:owner-id owner-id
                                         :name "Coselling.ai seller"
                                         :type "ai"
                                         :role "player"
                                         ;; Required by MoM. Nothing listens: the
                                         ;; seat is held by a person, and listings
                                         ;; are declared by this tool.
                                         :endpoint (str (or (gm-config/resolve-app-base-url) "")
                                                        "/seller-inbox")
                                         :player-group-id group-id})]
                           (when-not (ok? reg) (fail! "agent registration" reg))
                           (get-in reg [:body :agent/id])))
            claim (mom :post (str "/admin/games/" game-name "/claim-player")
                       {:agent-id (str agent-id)
                        :player-role "seller"
                        :roles ["seller"]
                        :player-roles ["seller"]
                        :player-name seller-name})]
        (when-not (ok? claim) (fail! "claim-player" claim))
        (println "seller player created:" seller-name)))))

(defn- slug->id [entries]
  (into {} (map (juxt :slug :id)) entries))

(defn- capability
  "A spec listing as Market expects it: taxonomy slugs resolved to registry ids."
  [listing categories hashtags]
  (let [ids (fn [index slugs]
              (mapv #(or (get index %)
                         (throw (ex-info "Unknown taxonomy slug" {:slug % :listing (:id listing)})))
                    slugs))]
    (-> listing
        (dissoc :category-slugs :hashtag-slugs)
        (assoc :category-ids (ids categories (:category-slugs listing))
               :hashtag-ids (ids hashtags (:hashtag-slugs listing))))))

(defn declare-listings!
  [game-name]
  ;; Registration is a prerequisite for listing. The default rate is 0 because
  ;; each listing carries its own.
  (seller-event game-name "market/set-seller-defaults" {:default-commission-bps 0})
  (let [categories (slug->id (get-in (seller-event game-name "market/query-categories" {})
                                     [:data :categories]))
        hashtags (slug->id (get-in (seller-event game-name "market/query-hashtags" {})
                                   [:data :hashtags]))
        existing (set (map :id (get-in (seller-event game-name "market/query-capabilities"
                                                     {:filters {:seller seller-name}})
                                       [:data :capabilities])))]
    (doseq [listing spec/offer-listings
            :let [cap (capability listing categories hashtags)]]
      (if (contains? existing (:id cap))
        (do (seller-event game-name "market/update-capability"
                          {:capability-id (:id cap) :updates (dissoc cap :id)})
            (println "updated listing:" (:id cap)))
        (do (seller-event game-name "market/declare-capability" {:capability cap})
            (println "declared listing:" (:id cap)))))))

(defn -main [& _]
  (let [game-name (or (env "GAME_NAME") "coselling-ai")
        owner-id (or (env "SELLER_OWNER_ID")
                     (throw (ex-info "SELLER_OWNER_ID is required" {})))
        group-id (or (env "SELLER_PLAYER_GROUP_ID") "coselling-ai-seller")]
    (when (= group-id (env "PLAYER_GROUP_ID"))
      (throw (ex-info "The seller's player group must differ from the GM's" {:group group-id})))
    ;; Trade the game token for a service JWT, as the GM does at startup.
    (when-not (gm-config/refresh-service-token! (gm-identity/mom-url nil))
      (println "warning: no service JWT; falling back to the static game token"))
    (ensure-seller-player! game-name owner-id group-id)
    (declare-listings! game-name)
    (shutdown-agents)
    (System/exit 0)))
