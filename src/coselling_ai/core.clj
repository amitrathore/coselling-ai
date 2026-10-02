(ns coselling-ai.core
  (:require [coselling-ai.handlers :refer [app]]
            [coselling-ai.spec :as spec]
            [game.gm.config :as gm-config]
            [game.gm.runtime :as gm-runtime])
  (:gen-class))

(defn -main [& args]
  (println "--- Starting Coselling.ai Game Master ---")
  (let [cfg (gm-config/build-config args)]
    (gm-runtime/start-gm!
      (assoc cfg :game-spec spec/game-spec :agent-name "Coselling.ai GM")
      app)))
