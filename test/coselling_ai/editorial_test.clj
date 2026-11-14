(ns coselling-ai.editorial-test
  "The Attention boundary an AI assistant drives through MoM's MCP server:
   a brief becomes a published blog post, and every way of doing that as
   someone else, or twice, is refused."
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [coselling-ai.blog :as blog]
            [coselling-ai.editorial :as editorial]
            [coselling-ai.handlers :as handlers]))

(defn- member [player-name roles]
  {:type "member" :player-name player-name :membership-id (str "m-" player-name)
   :roles roles})

(def amit (member "amit" ["audience" "coseller" "strategist" "creator"]))
(def ari (member "ari" ["audience" "coseller"]))

(def post-content
  {:title "Why we pay for outcomes"
   :summary "Coselling pays the people whose conversations move a sale, only when it happens."
   :body "## The idea\n\nShare what you like. **Never close.**\n\n<script>alert(1)</script>\n\n[bad](javascript:alert(1))"})

(defn- with-ready [f]
  (reset! @#'editorial/registration {:ready true})
  (try (f) (finally (reset! @#'editorial/registration {:ready false :reason "test"}))))

(use-fixtures :each with-ready)

(defn- event
  ([state caller type data] (event state caller type data nil))
  ([state caller type data idempotency-key]
   (handlers/dispatch (cond-> {:event/type type :event/data data :game-state state
                               :game/id "g-1" :caller caller}
                        idempotency-key (assoc :idempotency-key idempotency-key)))))

(defn- apply-result
  "What MoM does with a successful result: replace each returned namespace."
  [state result]
  (merge state (dissoc (:updated-state result) :gm)))

(defn- run
  "Thread `state` through successful events, failing loudly on the first refusal."
  [state & steps]
  (reduce (fn [state [caller type data]]
            (let [result (event state caller type data)]
              (is (:success result) (str type " " (pr-str (:error result))))
              (apply-result state result)))
          state steps))

(defn- task-id [state]
  (first (keys (get-in state [:attention :tasks]))))

(defn- brief [] (run {} [amit "attention/create-task" {:player-name "amit" :spec {:title "Outcomes post"}}]))

(defn- submitted []
  (let [state (brief)
        id (name (task-id state))]
    [(run state
          [amit "attention/assign-task" {:player-name "amit" :task-id id :assignee-name "amit"}]
          [amit "attention/claim-task" {:player-name "amit" :task-id id}]
          [amit "attention/submit-draft" {:player-name "amit" :task-id id :content post-content}])
     id]))

(deftest a-brief-becomes-a-published-post
  (let [[state id] (submitted)
        state (run state [amit "attention/review-task"
                          {:player-name "amit" :task-id id :decision "approve"
                           :feedback "Amit approved in chat: \"ship it\""}])
        post (first (get-in state [:attention :feed]))]
    (is (= "why-we-pay-for-outcomes" (:slug post)))
    (is (= post-content (:payload post)))
    (testing "the approval is audited with the statement the assistant acted on"
      (let [entry (last (get-in state [:attention :editorial-audit]))]
        (is (= "attention/review-task" (:operation entry)))
        (is (= "amit" (get-in entry [:actor :player-name])))
        (is (str/includes? (:approval-statement entry) "ship it"))))))

(deftest acting-as-someone-else-is-refused
  (let [result (event {} ari "attention/create-task" {:player-name "amit" :spec {:title "x"}})]
    (is (= "caller-mismatch" (get-in result [:error :code])))))

(deftest a-seat-without-the-role-is-refused
  (testing "an audience/coseller seat cannot brief, even claiming roles in the request"
    (let [result (event {} ari "attention/create-task"
                        {:player-name "ari" :roles ["strategist"] :spec {:title "x"}})]
      (is (= "role-required" (get-in result [:error :code])))))
  (testing "nor read the task board, which holds drafts"
    (let [result (event (brief) ari "attention/query-tasks" {:player-name "ari"})]
      (is (= "role-required" (get-in result [:error :code]))))))

(deftest a-request-with-no-verified-caller-is-refused
  (let [result (event {} nil "attention/create-task" {:player-name "amit" :spec {:title "x"}})]
    (is (= "caller-required" (get-in result [:error :code])))))

(deftest attention-waits-for-mom-to-protect-it
  (reset! @#'editorial/registration {:ready false :reason "Protect Attention in Portal."})
  (let [result (event {} amit "attention/create-task" {:player-name "amit" :spec {:title "x"}})]
    (is (= 503 (:status result)))
    (is (= "attention-registration-pending" (get-in result [:error :code])))))

(deftest a-post-must-have-title-summary-and-body
  (let [state (brief)
        id (name (task-id state))
        state (run state
                   [amit "attention/assign-task" {:player-name "amit" :task-id id :assignee-name "amit"}]
                   [amit "attention/claim-task" {:player-name "amit" :task-id id}])]
    (is (= "invalid-post"
           (get-in (event state amit "attention/submit-draft"
                          {:player-name "amit" :task-id id :content (dissoc post-content :summary)})
                   [:error :code])))
    (is (= "invalid-post"
           (get-in (event state amit "attention/submit-draft"
                          {:player-name "amit" :task-id id
                           :content (assoc post-content :featured_image "http://insecure.example/a.png")})
                   [:error :code])))))

(deftest a-retried-request-does-not-repeat
  (let [state (brief)
        data {:player-name "amit" :spec {:title "Second brief"}}
        first-result (event state amit "attention/create-task" data "key-1")
        state (apply-result state first-result)
        replay (event state amit "attention/create-task" data "key-1")]
    (is (:success first-result))
    (testing "the same key and content returns the first result and changes nothing"
      (is (= (select-keys first-result [:success :data :events]) replay))
      (is (nil? (:updated-state replay))))
    (testing "the same key with different content is a conflict"
      (is (= "idempotency-conflict"
             (get-in (event state amit "attention/create-task"
                            {:player-name "amit" :spec {:title "Changed"}} "key-1")
                     [:error :code]))))))

(deftest only-the-owner-or-mom-reads-the-whole-state
  (is (= "owner-required" (get-in (event {:attention {}} amit "gm/query-state" {}) [:error :code])))
  (is (:success (event {:attention {}} {:type "system"} "gm/query-state" {}))))

(deftest drafts-never-reach-the-public-read-model
  (let [[state _] (submitted)
        public (handlers/public-game-state state)]
    (is (every? #{:brand-bible :feed} (keys (:attention public))))
    (is (not (str/includes? (pr-str public) "Why we pay for outcomes")))))

;; --- The blog ---

(defn- GET [uri]
  (handlers/app {:request-method :get :uri uri :scheme :https :headers {"host" "coselling.example"}}))

(defn- body [resp]
  (let [b (:body resp)] (if (instance? java.io.InputStream b) (slurp b) (str b))))

(deftest a-published-post-is-served-on-the-blog
  (let [[state id] (submitted)
        state (run state [amit "attention/review-task" {:player-name "amit" :task-id id :decision "approve"}])]
    (blog/set-feed! (get-in state [:attention :feed]))
    (try
      (let [page (GET "/pages/blog/why-we-pay-for-outcomes/")
            html (body page)]
        (is (= 200 (:status page)))
        (is (str/includes? html "<h1>Why we pay for outcomes</h1>"))
        (is (str/includes? html "<h2>The idea</h2>"))
        (is (str/includes? html "og:description\" content=\"Coselling pays"))
        (testing "AI-written Markdown cannot inject script or javascript: links"
          (is (not (str/includes? html "<script>alert")))
          (is (not (str/includes? html "javascript:alert"))))
        (testing "the site chrome works one level deeper"
          (is (str/includes? html "<script src=\"/script.js\">"))
          (is (str/includes? html "href=\"/pages/share/\"")))
        (is (= 302 (:status (GET "/pages/blog/why-we-pay-for-outcomes")))))
      (let [index (body (GET "/pages/blog/"))]
        (is (str/includes? index "href=\"/pages/blog/why-we-pay-for-outcomes/\""))
        (is (str/includes? index "/pages/welcome-to-the-coselling-blog/")))
      (is (= 404 (:status (GET "/pages/blog/no-such-post/"))))
      (finally (blog/set-feed! [])))))
