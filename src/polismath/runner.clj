(ns polismath.runner
  "This namespace is responsible for running systems"
  (:require [polismath.system :as system :refer [darwin-system onyx-system simulator-system]]
            [polismath.stormspec :as stormspec :refer [storm-system]]
            [polismath.utils :as utils]
            [clojure.newtools.cli :as cli]
            [clojure.tools.namespace.repl :as namespace.repl]
            [clojure.tools.logging :as log]
            [clojure.string :as string]
            [com.stuartsierra.component :as component]))


(defonce system nil)

;; Should build this to be an atom, and build something that intiates this state from a config file.
;; So when you reset, it reboots all systems.

(defn init!
  ([system-map-fn config-overrides]
   (alter-var-root #'system
     (constantly (utils/apply-kwargs component/system-map (system-map-fn config-overrides)))))
  ([system-map-fn]
   (init! system-map-fn {})))

(defn start! []
  (alter-var-root #'system component/start))

(defn stop! []
  (alter-var-root #'system
    (fn [s] (when s (component/stop s)))))

(defn run!
  ([system-map-fn config-overrides]
   (init! system-map-fn config-overrides)
   (start!))
  ([system-map-fn]
   (run! system-map-fn {})))

;(defonce -runner! nil)
(defn -runner! [] (run! (system/base-system {})))

(defn system-reset!
  ([system-map-fn config-overrides]
   (stop!)
   (alter-var-root #'-runner! (partial run! system-map-fn config-overrides))
   ;; Not sure if this -runner! thing will work, but giving it a try. If it does we can stashthe system and
   ;; config-overrides as well.
   (namespace.repl/refresh :after 'polismath.system/runner!)))


(def subcommands
  {"storm" storm-system
   "darwin" darwin-system
   "onyx" onyx-system
   "simulator" simulator-system})


(def cli-options
  "Has the same options as simulation if simulations are run"
  [["-n" "--name" "Cluster name; triggers submission to cluster" :default nil]
   ["-r" "--recompute"]])

(defn usage [options-summary]
  (->> ["Polismath stormspec"
        "Usage: lein run [subcommand] [options]"
        ""
        "Subcommand options: storm, darwin, onyx, simulator"
        ""
        "Other options:"
        options-summary]
   (string/join \newline)))

(defn -main [& args]
  (let [{:keys [arguments options errors summary]} (cli/parse-opts args cli-options)]
    (log/info "Submitting storm topology")
    (cond
      (:help options)   (utils/exit 0 (usage summary))
      (:errors options) (utils/exit 1 (str "Found the following errors:" \newline (:errors options)))
      :else 
      (let [system-map-fn (subcommands (first arguments))]
        (start! system-map-fn)))))

(comment
  (try
    ;(stop!)
    (run! storm-system)
    :ok (catch Exception e (.printStackTrace e) e))
  )

