(ns golem.app
  "Steps 4-5: dynamic Reitit routes plus validation/hydration middleware."
  (:require [jsonista.core :as j]
            [malli.core :as m]
            [malli.error :as me]
            [malli.transform :as mt]
            [reitit.ring :as ring]
            [ring.middleware.params :refer [wrap-params]]
            [golem.gen :as gen]
            [golem.hydrate :as hydrate]
            [golem.openapi :as openapi]
            [golem.store :as store]))

(def ^:private mapper (j/object-mapper {:decode-key-fn keyword}))

(def ^:private decoder (mt/transformer (mt/strip-extra-keys-transformer) (mt/json-transformer)))

(defn- json [status body] {:status status :body body})

;; ---- global middleware -------------------------------------------------

(defn wrap-cors [handler]
  (let [hdrs {"Access-Control-Allow-Origin" "*"
              "Access-Control-Allow-Methods" "GET,POST,PUT,PATCH,DELETE,OPTIONS"
              "Access-Control-Allow-Headers" "Content-Type,Authorization"}]
    (fn [req]
      (if (= :options (:request-method req))
        {:status 204 :headers hdrs}
        (update (handler req) :headers merge hdrs)))))

(defn- extra-keys
  "Paths of keys present in orig but removed from stripped (the decoded copy)."
  [orig stripped path]
  (cond
    (and (map? orig) (map? stripped))
    (mapcat (fn [[k v]]
              (if (contains? stripped k)
                (extra-keys v (get stripped k) (conj path k))
                [(conj path k)]))
            orig)
    (and (sequential? orig) (sequential? stripped) (= (count orig) (count stripped))) 
    (mapcat (fn [i a b] (extra-keys a b (conj path i))) (range) orig stripped)
    :else []))

(defn- strict-errors
  "{:field [\"disallowed key\"]} for every unknown key in body, or nil."
  [body decoded]
  (when-let [paths (seq (extra-keys body decoded []))]
    (reduce #(assoc-in %1 %2 ["disallowed key"]) {} paths)))

(defn wrap-json [handler]
  (fn [req]
    (let [s (some-> (:body req) slurp)
          parsed (if (seq s)
                   (try (j/read-value s mapper) (catch Exception _ ::bad))
                   nil)]
      (if (= ::bad parsed)
        (-> (json 400 {:error "invalid JSON body"})
            (update :body j/write-value-as-string)
            (assoc-in [:headers "Content-Type"] "application/json"))
        (let [resp (handler (assoc req :body-params parsed))]
          (if (some? (:body resp))
            (-> resp
                (update :body j/write-value-as-string)
                (assoc-in [:headers "Content-Type"] "application/json"))
            resp))))))

(defn wrap-exceptions [handler]
  (fn [req]
    (try
      (handler req)
      (catch clojure.lang.ExceptionInfo ex
        (let [{:keys [type errors]} (ex-data ex)]
          (case type
            :invalid (json 400 {:error "validation failed" :errors errors})
            :not-found (json 404 {:error "not found"})
            :conflict (json 409 {:error "already exists"})
            :bad-request (json 400 {:error (ex-message ex)})
            (json 500 {:error (ex-message ex)}))))
      (catch Exception ex
        (json 500 {:error (ex-message ex)})))))

;; ---- per-entity middleware ---------------------------------------------

(defn wrap-validate-input
  "POST/PUT: hydrate server-owned fields, decode JSON, validate with Malli.
  Failure => 400 with human-readable errors. With :strict? unknown keys are errors too."
  [handler db e & [{:keys [strict?]}]]
  (fn [req]
    (let [body (:body-params req)
          put? (= :put (:request-method req))
          id (get-in req [:path-params :id])
          existing (when put? (store/fetch db e id))]
      (cond
        (not (map? body)) (json 400 {:error "body must be a JSON object"})
        (and put? (nil? existing)) (json 404 {:error "not found"})
        :else
        (let [hydrated (hydrate/hydrate db e body
                                        {:id id
                                         :existing (some-> existing (as-> x (m/encode (:schema e) x (mt/json-transformer))))})
              decoded (m/decode (:schema e) hydrated decoder)]
          (if-let [extra (when strict? (strict-errors hydrated decoded))]
            (json 400 {:error "validation failed" :errors extra})
            (if (m/validate (:schema e) decoded)
              (handler (assoc req :entity-body decoded))
              (json 400 {:error "validation failed"
                         :errors (me/humanize (m/explain (:schema e) decoded))}))))))))

(defn wrap-encode-output
  "Encodes 2xx entity bodies to their JSON representation (uuids, dates...)."
  [handler e]
  (let [enc #(m/encode (:schema e) % (mt/json-transformer))]
    (fn [req]
      (let [resp (handler req)]
        (cond-> resp
          (and (<= 200 (:status resp) 299) (sequential? (:body resp))) (update :body #(mapv enc %))
          (and (<= 200 (:status resp) 299) (map? (:body resp))) (update :body enc))))))

;; ---- routes --------------------------------------------------------------

(defn- entity-routes [db {:keys [path] :as e} opts]
  (let [not-found (fn [] (json 404 {:error "not found"}))
        by-id (fn [req] (store/fetch db e (get-in req [:path-params :id])))]
    [path {:middleware [[wrap-encode-output e]]}
     ["" {:get {:handler (fn [req] (json 200 (store/query db e (:query-params req))))}
          :post {:middleware [[wrap-validate-input db e opts]]
                 :handler (fn [req]
                            (let [item (store/insert! db e (:entity-body req))]
                              (assoc (json 201 item)
                                     :headers {"Location" (str path "/" (get item (:id-key e)))})))}}]
     ["/:id" {:get {:handler (fn [req] (if-let [item (by-id req)] (json 200 item) (not-found)))}
              :put {:middleware [[wrap-validate-input db e opts]]
                    :handler (fn [req]
                               (json 200 (store/replace! db e (get-in req [:path-params :id]) (:entity-body req))))}
              :patch {:handler (fn [req]
                                 (let [patch (:body-params req)]
                                   (if (map? patch)
                                     (let [decoded (m/decode (:schema e) patch decoder)]
                                       (if-let [extra (when (:strict? opts) (strict-errors patch decoded))]
                                         (json 400 {:error "validation failed" :errors extra})
                                         (json 200 (store/patch! db e (get-in req [:path-params :id]) decoded))))
                                     (json 400 {:error "body must be a JSON object"}))))}
              :delete {:handler (fn [req]
                                  (if (store/delete! db e (get-in req [:path-params :id]))
                                    {:status 204}
                                    (not-found)))}}]]))

(declare seed-db!)

(defn- reset-handler
  "POST /_reset[?seed=N]: empties the store and regenerates the seed data."
  [db reg seed-opts]
  (fn [req]
    (let [raw (get-in req [:query-params "seed"])
          seed (if raw (parse-long raw) (:seed seed-opts))]
      (if (nil? seed)
        (json 400 {:error "seed must be a whole number"})
        (do (reset! db {})
            (seed-db! db reg (assoc seed-opts :seed seed))
            (json 200 {:status "reset" :seed seed}))))))

(defn- meta-routes [{:keys [entities] :as reg} db seed-opts]
  (cond-> [["/_schemas" {:get {:handler (fn [_]
                                          (json 200 (for [[n e] entities]
                                                      {:entity n :path (:path e) :exposed (:expose? e)
                                                       :refs (sort (:refs e)) :schema (m/form (:schema e))})))}}]
           ["/_openapi" {:get {:handler (fn [_] (json 200 (openapi/openapi reg {:reset? (some? seed-opts)})))}}]]
    seed-opts (conj ["/_reset" {:post {:handler (reset-handler db reg seed-opts)}}])))

(defn seed-order
  "Exposed entities ordered so that every entity comes after the entities its
  foreign keys point at. Cycles are broken where they are first met."
  [entities]
  (let [visit (fn visit [{:keys [done visiting] :as st} k]
                (if (or (done k) (visiting k))
                  st
                  (let [deps (distinct (vals (:fks (get entities k))))
                        st (reduce visit (update st :visiting conj k) deps)]
                    (-> st (update :done conj k) (update :out conj k)))))
        {:keys [out]} (reduce visit {:done #{} :visiting #{} :out []} (keys entities))]
    (filter #(:expose? (get entities %)) out)))

(defn seed-db!
  "Populate the store with deterministic generated data. Entities are seeded in
  dependency order and foreign-key fields point at ids that exist."
  [db {:keys [entities]} opts]
  (reduce (fn [pool k]
            (let [e (get entities k)
                  items (gen/generate-items e (assoc opts :pool pool))]
              (store/seed! db e items)
              (assoc pool k (mapv #(get % (:id-key e)) items))))
          {} (seed-order entities))
  nil)

;; ---- simulation (latency, failures, auth) --------------------------------------

(defn parse-delay
  "\"300\" -> [300 300]; \"200-800\" -> [200 800]; nil -> nil. Milliseconds."
  [x]
  (when (some? x)
    (let [[_ lo hi] (re-matches #"\s*(\d+)\s*(?:-\s*(\d+)\s*)?" (str x))]
      (when-not lo
        (throw (ex-info (str "delay must be milliseconds like 300 or 200-800, got " (pr-str x)) {})))
      (let [lo (parse-long lo) hi (if hi (parse-long hi) lo)]
        (when (< hi lo)
          (throw (ex-info (str "delay range is backwards: " x) {})))
        [lo hi]))))

(def ^:private reserved-params #{"_status"})

(defn wrap-simulation
  "Frontend-testing aids, all off by default. Routes under /_ are never touched.
  opts: :delay [min-ms max-ms], :error-rate 0..1 (random 5xx), :auth true or a
  token string (Bearer; 401 when missing, 403 when wrong), :seed for repeatable
  failures. A request can also force a failure with an X-Golem-Status header or
  ?_status=N (400-599)."
  [handler {:keys [delay error-rate auth seed]}]
  (let [rnd (java.util.Random. (long (or seed 0)))]
    (fn [req]
      (if (.startsWith ^String (or (:uri req) "") "/_")
        (handler req)
        (let [forced (or (get-in req [:headers "x-golem-status"])
                         (get-in req [:query-params "_status"]))
              req (-> req (update :query-params dissoc "_status") (update :params dissoc "_status"))
              token (some->> (get-in req [:headers "authorization"]) (re-matches #"(?i)Bearer\s+(\S+)") second)]
          (when delay
            (let [[lo hi] delay]
              (Thread/sleep (long (if (= lo hi) lo (+ lo (.nextInt rnd (inc (- hi lo)))))))))
          (cond
            (and auth (nil? token))
            (assoc (json 401 {:error "authentication required"}) :headers {"WWW-Authenticate" "Bearer"})

            (and (string? auth) (not= auth token))
            (json 403 {:error "invalid token"})

            forced
            (let [n (try (Long/parseLong forced) (catch NumberFormatException _ nil))]
              (if (and n (<= 400 n 599))
                (json n {:error "simulated response" :status n})
                (json 400 {:error "X-Golem-Status / _status must be a status code from 400 to 599"})))

            (and error-rate (< (.nextDouble rnd) error-rate))
            (let [n (nth [500 502 503 504] (.nextInt rnd 4))]
              (json n {:error "simulated server error" :status n}))

            :else (handler req)))))))

(defn make-handler
  "Ring handler for the registry. With seed-opts ({:seed :count :size ...}, the
  options given to seed-db!) it also serves POST /_reset, which regenerates the data.
  opts: :strict? (reject unknown JSON fields) plus the wrap-simulation options."
  ([reg db] (make-handler reg db nil nil))
  ([reg db seed-opts] (make-handler reg db seed-opts nil))
  ([{:keys [entities] :as reg} db seed-opts opts]
  (-> (ring/ring-handler
       (ring/router (into (meta-routes reg db seed-opts)
                          (for [[_ e] entities :when (:expose? e)] (entity-routes db e opts)))
                    {:conflicts nil})
       (ring/create-default-handler
        {:not-found (constantly (json 404 {:error "not found"}))
         :method-not-allowed (constantly (json 405 {:error "method not allowed"}))}))
      wrap-exceptions
      (wrap-simulation (merge {:seed (:seed seed-opts)} opts))
      wrap-json
      wrap-params
      wrap-cors)))
