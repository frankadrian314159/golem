(ns golem.store
  "Step 3: in-memory datastore. One atom: {entity {:order [id ...] :items {id item}}}.
  Every mutation is re-validated against the entity's Malli schema."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]))

(defn create-db [] (atom {}))

(defn- item-id [e item] (str (get item (:id-key e))))

(defn- check! [e item]
  (when-not (m/validate (:schema e) item)
    (throw (ex-info "validation failed"
                    {:type :invalid
                     :errors (me/humanize (m/explain (:schema e) item))}))))

(defn- bucket [b] (or b {:order [] :items {}}))

(defn- deep-merge [a b]
  (if (and (map? a) (map? b)) (merge-with deep-merge a b) b))

(defn fetch [db e id]
  (get-in @db [(:name e) :items (str id)]))

(defn next-int-id [db e]
  (inc (reduce max 0 (keep #(when (integer? %) %) (map (:id-key e) (vals (get-in @db [(:name e) :items])))))))

(defn insert! [db e item]
  (check! e item)
  (let [id (item-id e item)]
    (swap! db update (:name e)
           (fn [b]
             (let [b (bucket b)]
               (when (contains? (:items b) id)
                 (throw (ex-info "already exists" {:type :conflict :id id})))
               (-> b (update :order conj id) (assoc-in [:items id] item)))))
    item))

(defn replace! [db e id item]
  (check! e item)
  (let [id (str id)]
    (swap! db update (:name e)
           (fn [b]
             (let [b (bucket b)]
               (when-not (contains? (:items b) id)
                 (throw (ex-info "not found" {:type :not-found})))
               (assoc-in b [:items id] item))))
    item))

(defn patch!
  "Deep-merges a partial update (nested fields included) and re-validates."
  [db e id patch]
  (let [id (str id)
        out (-> (swap! db update (:name e)
                       (fn [b]
                         (let [b (bucket b)
                               old (get-in b [:items id])]
                           (when-not old (throw (ex-info "not found" {:type :not-found})))
                           (let [merged (assoc (deep-merge old patch) (:id-key e) (get old (:id-key e)))]
                             (check! e merged)
                             (assoc-in b [:items id] merged)))))
                (get-in [(:name e) :items id]))]
    out))

(defn delete! [db e id]
  (let [id (str id)
        [old _] (swap-vals! db update (:name e)
                            (fn [b] (let [b (bucket b)]
                                      (-> b (update :items dissoc id)
                                          (update :order (fn [o] (vec (remove #{id} o))))))))]
    (contains? (get-in old [(:name e) :items]) id)))

(defn- path-get
  "Looks up a dotted path (address.city) by field name, ignoring namespaces."
  [item path]
  (reduce (fn [x seg]
            (when (map? x)
              (some (fn [[k v]] (when (= seg (name k)) v)) x)))
          item (str/split path #"\.")))

(defn query
  "Equality filters from query params; reserved: limit, offset."
  [db e params]
  (let [{:keys [order items]} (bucket (get @db (:name e)))
        {:strs [limit offset]} params
        filters (dissoc params "limit" "offset")]
    (cond->> (map items order)
      (seq filters) (filter (fn [item] (every? (fn [[k v]] (= v (str (path-get item k)))) filters)))
      offset (drop (parse-long offset))
      limit (take (parse-long limit))
      true vec)))

(defn seed! [db e items]
  (doseq [item items] (insert! db e item)))
