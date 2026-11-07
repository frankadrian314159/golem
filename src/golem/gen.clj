(ns golem.gen
  "Step 2: seeded data generation on top of malli.generator / test.check."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [malli.core :as m]
            [malli.generator :as mg])
  (:import (clojure.lang MapEntry)
           (java.time Instant)))

(defn- email-from [s]
  (let [local (->> (str/lower-case s) (filter #(re-matches #"[a-z0-9]" (str %))) (take 8) (apply str))]
    (str local (mod (hash s) 100000) "@example.com")))

(defn- instant-from [s]
  (str (Instant/ofEpochSecond (+ 1500000000 (mod (hash s) 100000000)))))

(def default-overrides
  "Field name -> (fn [generated-string] replacement). Pure and derived from the
  generated value, so overrides stay deterministic under a fixed seed."
  {"email" email-from
   "created_at" instant-from
   "updated_at" instant-from
   "createdAt" instant-from
   "updatedAt" instant-from})

(defn- apply-overrides [overrides data]
  (walk/postwalk
   (fn [x]
     (if (and (map-entry? x) (string? (val x)) (or (keyword? (key x)) (string? (key x))))
       (if-let [f (get overrides (name (key x)))]
         (MapEntry. (key x) (f (val x)))
         x)
       x))
   data))

(defn generate-item [{:keys [schema]} seed {:keys [size overrides] :or {size 10}}]
  (let [raw (mg/generate schema {:seed seed :size size})
        over (apply-overrides (or overrides default-overrides) raw)]
    (if (m/validate schema over) over raw)))

(defn link-foreign-keys
  "Replaces the value of every foreign-key field (see :fks on the entity) with an
  id drawn from pool ({target-entity [id ...]}). Draws come from a Random seeded
  with seed, so the result is repeatable. Fields whose target has no ids yet, and
  nil values, are left alone; if the linked item no longer satisfies the schema
  the original is returned."
  [{:keys [schema fks]} pool seed item]
  (if (empty? fks)
    item
    (let [rnd (java.util.Random. seed)
          linked (walk/postwalk
                  (fn [x]
                    (if (and (map-entry? x) (some? (val x)) (or (keyword? (key x)) (string? (key x))))
                      (let [ids (some->> (get fks (name (key x))) (get pool) seq vec)]
                        (if ids
                          (MapEntry. (key x) (nth ids (.nextInt rnd (count ids))))
                          x))
                      x))
                  item)]
      (if (m/validate schema linked) linked item))))

(defn generate-items
  "n deterministic items for an entity: same seed => same data. opts may carry
  :pool {entity [id ...]} of already-seeded ids for foreign keys to point at."
  [{:keys [name id-key id-type] :as e} {:keys [seed count pool] :or {seed 0 count 5} :as opts}]
  (let [base (+ seed (hash (clojure.core/name name)))]
    (->> (range count)
         (map (fn [i]
                (->> (cond-> (generate-item e (+ base i) opts)
                       (contains? #{:int 'int?} id-type) (assoc id-key (inc i)))
                     (link-foreign-keys e pool (+ base i)))))
         (reduce (fn [[seen out] item]
                   (let [id (str (get item id-key))]
                     (if (seen id) [seen out] [(conj seen id) (conj out item)])))
                 [#{} []])
         second)))
