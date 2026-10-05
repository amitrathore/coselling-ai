(defproject coselling-ai "0.1.0-SNAPSHOT"
  :description "Coselling.ai — performance-paid distribution networks, run as an Intergraph game"
  :license {:name "MIT"
            :url "https://opensource.org/licenses/MIT"}
  ;; gm-lib comes from Clojars. Never `lein install` a local copy: it shadows the
  ;; published artifact and makes the build green on exactly one machine.
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [ai.intergraph/game-master "0.1.20-SNAPSHOT"]
                 [clj-http "3.12.3"]
                 [cheshire "5.11.0"]
                 [compojure "1.7.0"]
                 [ring/ring-json "0.5.1"]
                 [ring/ring-defaults "0.3.4"]
                 [ring/ring-jetty-adapter "1.9.6"]]
  :main ^:skip-aot coselling-ai.core
  :uberjar-name "coselling-ai-standalone.jar"
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})
