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
   :coerce {:port :long :seed :long :count :long :size :long
            :delay :string :error-rate :double :strict :boolean :watch :boolean}})

(def usage
  "Usage: bb mock-server (-s <malli: .edn|.clj|ns/var> | --specs <clojure.spec ns|.clj>) [-p 3000] [--seed N] [--count 5] [--size 10] [--persist file.edn]
                      [--strict] [--watch] [--delay 200-800] [--error-rate 0.1] [--auth [token]]")

(defn- check-opts! [{:keys [error-rate]}]
  (when (and error-rate (not (<= 0 error-rate 1)))
    (throw (ex-info "--error-rate must be between 0 and 1" {:error-rate error-rate}))))

(defn- build-registry [opts]
  (let [defs (imp/load-defs opts)]
    (doseq [w (:warnings (meta defs))] (println "warning:" w))
    (registry/build defs)))

(defn- handler-opts [{:keys [strict delay error-rate auth]}]
  {:strict? (boolean strict)
   :delay (app/parse-delay delay)
   :error-rate error-rate
   :auth auth})

(defn- source-files
  "Schema/spec sources that are plain files (ns/var sources can't be watched)."
  [{:keys [schemas specs]}]
  (->> [schemas specs]
       (filter some?)
       (map #(java.io.File. ^String %))
       (filter #(.isFile ^java.io.File %))))

(defn- watch!
  "Polls the source files and calls (reload!) whenever one changes. Returns a
  stop function."
  [files reload!]
  (let [stamp #(mapv (fn [^java.io.File f] (.lastModified f)) files)
        running (atom true)
        t (Thread. (fn []
                     (loop [seen (stamp)]
                       (when @running
                         (Thread/sleep 400)
                         (let [now (stamp)]
                           (when (not= now seen) (reload!))
                           (recur now)))))
                   "golem-watch")]
    (.setDaemon t true)
    (.start t)
    #(reset! running false)))

(defn start!
  "Boots the server; returns {:server s :db db :seed seed ...}. Options are the
  CLI options as keywords. With :watch the schema files are reloaded on change
  (see reload! in the result)."
  [{:keys [port seed count size persist watch] :as opts :or {port 3000 count 5 size 10}}]
  (check-opts! opts)
  (let [seed (or seed (System/nanoTime))
        reg (atom (build-registry opts))
        db (store/create-db)
        seed-opts {:seed seed :count count :size size}
        hopts (handler-opts opts)
        restored? (and persist (store/load! db persist))
        handler (atom nil)
        install! #(reset! handler (app/make-handler @reg db seed-opts hopts))
        reload! (fn []
                  (try
                    (let [new-reg (build-registry opts)]
                      (reset! reg new-reg)
                      ;; persisted data is kept; only entities without data are seeded
                      (if persist
                        (let [have (set (keys @db))]
                          (app/seed-db! db (update new-reg :entities #(into (empty %) (remove (comp have key)) %))
                                        seed-opts))
                        (do (reset! db {}) (app/seed-db! db new-reg seed-opts)))
                      (install!)
                      (println "reloaded schemas"))
                    (catch Throwable t
                      (println "warning: reload failed, keeping the previous schemas -" (ex-message t))))
                  nil)]
    (when-not restored?
      (app/seed-db! db @reg seed-opts))
    (when persist
      (store/save! db persist)
      (store/autosave! db persist))
    (install!)
    (let [stop-watch (when watch
                       (let [files (source-files opts)]
                         (if (seq files)
                           (watch! files reload!)
                           (do (println "warning: --watch needs --schemas/--specs to be a file; ignoring") nil))))]
      {:server (jetty/run-jetty (fn [req] (@handler req)) {:port port :join? false})
       :db db :seed seed :registry @reg :port port :restored? (boolean restored?)
       :reload! reload! :stop-watch stop-watch :options hopts})))

(defn -main [& args]
  (let [opts (cli/parse-opts args cli-spec)]
    (if (or (:help opts) (not (or (:schemas opts) (:specs opts))))
      (println usage)
      (let [{:keys [seed registry port restored? options]} (start! opts)]
        (println (str "Mock server on http://localhost:" port "  (seed " seed ", reuse with --seed " seed ")"))
        (when (:persist opts)
          (println (str "  state " (if restored? "restored from " "saved to ") (:persist opts))))
        (doseq [[_ e] (:entities registry) :when (:expose? e)]
          (println (str "  " (:path e) "  [GET POST]   " (:path e) "/:id  [GET PUT PATCH DELETE]")))
        (println "  /_schemas  [GET]   /_openapi  [GET]   /_reset  [POST]")
        (when (:strict? options) (println "  strict: unknown JSON fields are rejected"))
        (when (:watch opts) (println "  watching schema files for changes"))
        (when-let [[lo hi] (:delay options)] (println (str "  simulated latency: " lo (when (not= lo hi) (str "-" hi)) " ms")))
        (when (:error-rate options) (println (str "  simulated failures: " (:error-rate options) " of requests get a 5xx")))
        (when (:auth options)
          (println (str "  auth required: Bearer " (if (string? (:auth options)) "<token given>" "<any token>"))))))))
