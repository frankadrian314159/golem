(ns golem.main
  "Step 6: CLI entry point + Jetty."
  (:require [babashka.cli :as cli]
            [golem.app :as app]
            [golem.import :as imp]
            [golem.registry :as registry]
            [golem.store :as store]
            [ring.adapter.jetty :as jetty])
  (:gen-class))

(def cli-spec
  {:alias {:s :schemas :p :port :h :help}
   :coerce {:port :long :seed :long :count :long :size :long}})

(def usage
  "Usage: bb mock-server (-s <malli: .edn|.clj|ns/var> | --specs <clojure.spec ns|.clj>) [-p 3000] [--seed N] [--count 5] [--size 10]")

(defn start!
  "Boots the server; returns {:server s :db db :seed seed}."
  [{:keys [port seed count size] :as opts :or {port 3000 count 5 size 10}}]
  (let [seed (or seed (System/nanoTime))
        defs (imp/load-defs opts)
        reg (registry/build defs)
        db (store/create-db)]
    (doseq [w (:warnings (meta defs))] (println "warning:" w))
    (app/seed-db! db reg {:seed seed :count count :size size})
    {:server (jetty/run-jetty (app/make-handler reg db) {:port port :join? false})
     :db db :seed seed :registry reg :port port}))

(defn -main [& args]
  (let [opts (cli/parse-opts args cli-spec)]
    (if (or (:help opts) (not (or (:schemas opts) (:specs opts))))
      (println usage)
      (let [{:keys [seed registry port]} (start! opts)]
        (println (str "Mock server on http://localhost:" port "  (seed " seed ", reuse with --seed " seed ")"))
        (doseq [[_ e] (:entities registry) :when (:expose? e)]
          (println (str "  " (:path e) "  [GET POST]   " (:path e) "/:id  [GET PUT PATCH DELETE]")))
        (println "  /_schemas  [GET]")))))
