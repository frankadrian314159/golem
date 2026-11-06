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

(defn generate-items
  "n deterministic items for an entity: same seed => same data."
  [{:keys [name id-key id-type] :as e} {:keys [seed count] :or {seed 0 count 5} :as opts}]
  (let [base (+ seed (hash (clojure.core/name name)))]
    (->> (range count)
         (map (fn [i]
                (cond-> (generate-item e (+ base i) opts)
                  (contains? #{:int 'int?} id-type) (assoc id-key (inc i)))))
         (reduce (fn [[seen out] item]
                   (let [id (str (get item id-key))]
                     (if (seen id) [seen out] [(conj seen id) (conj out item)])))
                 [#{} []])
         second)))
