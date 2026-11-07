(ns golem.store
  "Step 3: in-memory datastore. One atom: {entity {:order [id ...] :items {id item}}}.
  Every mutation is re-validated against the entity's Malli schema."
  (:require [clojure.edn]
            [clojure.string :as str]
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

(defn- merge-patch
  "RFC 7396 JSON Merge Patch. Maps merge recursively, anything else replaces.
  With null-mode :remove a nil value deletes the key; with :keep it is stored."
  [mode a b]
  (if (map? b)
    (reduce-kv (fn [acc k v]
                 (if (and (nil? v) (= mode :remove))
                   (dissoc acc k)
                   (assoc acc k (merge-patch mode (get acc k) v))))
               (if (map? a) a {})
               b)
    b))

(defn fetch [db e id]
  (get-in @db [(:name e) :items (str id)]))

(defn- max-int-id [b e]
  (reduce max 0 (keep #(when (integer? %) %) (map (:id-key e) (vals (:items b))))))

(defn next-int-id
  "Atomically reserves and returns the next integer id for the entity. Two
  concurrent callers never get the same id, and ids are not reused after a delete."
  [db e]
  (let [[old _] (swap-vals! db update (:name e)
                            (fn [b]
                              (let [b (bucket b)]
                                (assoc b :next-id (inc (or (:next-id b) (inc (max-int-id b e))))))))
        b (bucket (get old (:name e)))]
    (or (:next-id b) (inc (max-int-id b e)))))

(defn insert! [db e item]
  (check! e item)
  (let [id (item-id e item)]
    (swap! db update (:name e)
           (fn [b]
             (let [b (bucket b)]
               (when (contains? (:items b) id)
                 (throw (ex-info "already exists" {:type :conflict :id id})))
               (cond-> (-> b (update :order conj id) (assoc-in [:items id] item))
                 (and (:next-id b) (integer? (get item (:id-key e))))
                 (update :next-id max (inc (get item (:id-key e))))))))
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
  "Applies a JSON Merge Patch (nested fields included) and re-validates. A null
  deletes the key; if that leaves the record invalid (e.g. a required nullable
  field) the null is stored instead."
  [db e id patch]
  (let [id (str id)]
    (-> (swap! db update (:name e)
             (fn [b]
               (let [b (bucket b)
                     old (get-in b [:items id])
                     _ (when-not old (throw (ex-info "not found" {:type :not-found})))
                     build (fn [mode] (assoc (merge-patch mode old patch) (:id-key e) (get old (:id-key e))))
                     removed (build :remove)
                     kept (build :keep)
                     merged (cond (m/validate (:schema e) removed) removed
                                  (m/validate (:schema e) kept) kept
                                  :else removed)]
                 (check! e merged)
                 (assoc-in b [:items id] merged))))
        (get-in [(:name e) :items id]))))

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

(defn- bad-request! [msg]
  (throw (ex-info msg {:type :bad-request})))

(defn- unwrap
  "Peels :ref / :maybe / :schema wrappers off a Malli schema."
  [schema]
  (case (m/type schema)
    :ref (recur (m/deref schema))
    (:maybe :schema) (recur (first (m/children schema)))
    schema))

(defn- known-path?
  "Can the dotted path be a field of schema? Optimistic: anything Malli can't
  tell us statically (:or, :multi, :any, ...) is accepted."
  [schema path]
  (loop [sch schema [seg & more] (str/split path #"\.")]
    (let [sch (unwrap sch)]
      (if (not= :map (m/type sch))
        (not (contains? #{:int :string :boolean :double :keyword :uuid :vector :sequential :set :enum}
                        (m/type sch)))
        (if-let [child (some (fn [[k _ c]] (when (= seg (name k)) c)) (m/children sch))]
          (if more (recur child more) true)
          false)))))

(defn- whole-number [params k]
  (when-let [v (get params k)]
    (let [v (if (sequential? v) (bad-request! (str k " may be given only once")) v)
          n (try (Long/parseLong v) (catch NumberFormatException _ nil))]
      (if (and n (<= 0 n))
        n
        (bad-request! (str k " must be a non-negative whole number"))))))

(defn query
  "Equality filters from query params; reserved: limit, offset. A repeated
  filter (?a=1&a=2) matches either value. Unknown fields and malformed
  limit/offset throw {:type :bad-request}."
  [db e params]
  (let [{:keys [order items]} (bucket (get @db (:name e)))
        limit (whole-number params "limit")
        offset (whole-number params "offset")
        filters (dissoc params "limit" "offset")]
    (doseq [k (keys filters)]
      (when-not (known-path? (:schema e) k)
        (bad-request! (str "unknown filter field: " k))))
    (cond->> (map items order)
      (seq filters) (filter (fn [item]
                              (every? (fn [[k v]]
                                        (contains? (set (if (sequential? v) v [v]))
                                                   (str (path-get item k))))
                                      filters)))
      offset (drop offset)
      limit (take limit)
      true vec)))

(defn seed! [db e items]
  (doseq [item items] (insert! db e item)))

;; ---- persistence --------------------------------------------------------------
;; The whole store is one EDN value ({entity {:order [...] :items {...}}}); uuids
;; and instants round-trip through the #uuid / #inst tags.

(defn save!
  "Writes the current state to path as EDN (via a temp file, so a crash never
  leaves a half-written snapshot)."
  [db path]
  (let [f (java.io.File. ^String path)
        tmp (java.io.File. (str path ".tmp"))]
    (when-let [parent (.getParentFile (.getAbsoluteFile f))] (.mkdirs parent))
    (binding [*print-namespace-maps* false]
      (spit tmp (pr-str @db)))
    (java.nio.file.Files/move (.toPath tmp) (.toPath f)
                              (into-array java.nio.file.CopyOption
                                          [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
    path))

(defn load!
  "Replaces the state with the snapshot at path. Returns true if a snapshot was
  loaded, false if the file does not exist."
  [db path]
  (let [f (java.io.File. ^String path)]
    (when (.exists f)
      (let [state (try (clojure.edn/read-string (slurp f))
                       (catch Exception ex
                         (throw (ex-info (str "Cannot read snapshot " path ": " (ex-message ex))
                                         {:path path}))))]
        (when-not (map? state)
          (throw (ex-info (str "Snapshot " path " is not a store snapshot") {:path path})))
        (reset! db state)
        true))))

(defn autosave!
  "Saves to path after every change to db. Returns db."
  [db path]
  (let [lock (Object.)]
    (add-watch db ::autosave
               (fn [_ _ _ _]
                 (try (locking lock (save! db path))
                      (catch Exception ex
                        (binding [*out* *err*]
                          (println "warning: could not save" path "-" (ex-message ex))))))))
  db)
