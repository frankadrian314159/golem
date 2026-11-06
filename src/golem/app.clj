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
            (json 500 {:error (ex-message ex)}))))
      (catch Exception ex
        (json 500 {:error (ex-message ex)})))))

;; ---- per-entity middleware ---------------------------------------------

(defn wrap-validate-input
  "POST/PUT: hydrate server-owned fields, decode JSON, validate with Malli.
  Failure => 400 with human-readable errors."
  [handler db e]
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
          (if (m/validate (:schema e) decoded)
            (handler (assoc req :entity-body decoded))
            (json 400 {:error "validation failed"
                       :errors (me/humanize (m/explain (:schema e) decoded))})))))))

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

(defn- entity-routes [db {:keys [path] :as e}]
  (let [not-found (fn [] (json 404 {:error "not found"}))
        by-id (fn [req] (store/fetch db e (get-in req [:path-params :id])))]
    [path {:middleware [[wrap-encode-output e]]}
     ["" {:get {:handler (fn [req] (json 200 (store/query db e (:query-params req))))}
          :post {:middleware [[wrap-validate-input db e]]
                 :handler (fn [req]
                            (let [item (store/insert! db e (:entity-body req))]
                              (assoc (json 201 item)
                                     :headers {"Location" (str path "/" (get item (:id-key e)))})))}}]
     ["/:id" {:get {:handler (fn [req] (if-let [item (by-id req)] (json 200 item) (not-found)))}
              :put {:middleware [[wrap-validate-input db e]]
                    :handler (fn [req]
                               (json 200 (store/replace! db e (get-in req [:path-params :id]) (:entity-body req))))}
              :patch {:handler (fn [req]
                                 (let [patch (:body-params req)]
                                   (if (map? patch)
                                     (json 200 (store/patch! db e (get-in req [:path-params :id])
                                                             (m/decode (:schema e) patch decoder)))
                                     (json 400 {:error "body must be a JSON object"}))))}
              :delete {:handler (fn [req]
                                  (if (store/delete! db e (get-in req [:path-params :id]))
                                    {:status 204}
                                    (not-found)))}}]]))

(defn- meta-routes [{:keys [entities]}]
  [["/_schemas" {:get {:handler (fn [_]
                                  (json 200 (for [[n e] entities]
                                              {:entity n :path (:path e) :exposed (:expose? e)
                                               :refs (sort (:refs e)) :schema (m/form (:schema e))})))}}]])

(defn seed-db!
  "Populate the store with deterministic generated data."
  [db {:keys [entities]} opts]
  (doseq [[_ e] entities :when (:expose? e)]
    (store/seed! db e (gen/generate-items e opts))))

(defn make-handler [{:keys [entities] :as reg} db]
  (-> (ring/ring-handler
       (ring/router (into (meta-routes reg)
                          (for [[_ e] entities :when (:expose? e)] (entity-routes db e)))
                    {:conflicts nil})
       (ring/create-default-handler
        {:not-found (constantly (json 404 {:error "not found"}))
         :method-not-allowed (constantly (json 405 {:error "method not allowed"}))}))
      wrap-exceptions
      wrap-json
      wrap-params
      wrap-cors))
