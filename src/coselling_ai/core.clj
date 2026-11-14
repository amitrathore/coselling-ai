(ns coselling-ai.core
  (:require [coselling-ai.blog :as blog]
            [coselling-ai.handlers :refer [app refresh-published!]]
            [coselling-ai.spec :as spec]
            [game.gm.config :as gm-config]
            [game.gm.runtime :as gm-runtime])
  (:gen-class))

(defn -main [& args]
  (println "--- Starting Coselling.ai Game Master ---")
  (let [cfg (gm-config/build-config args)]
    (gm-runtime/start-gm!
      (assoc cfg :game-spec spec/game-spec :agent-name "Coselling.ai GM"
             ;; Attention's registration gate and the blog's feed, every 30s.
             :on-ready (fn [_] (blog/start! 30000 refresh-published!)))
      app)))
