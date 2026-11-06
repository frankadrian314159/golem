(ns golem.registry
  "Step 1: parse user schemas into Malli ASTs and register them centrally."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [malli.core :as m]
            [malli.registry :as mr]))

(defn pluralize [s]
  (cond
    (re-find #"[^aeiou]y$" s) (str (subs s 0 (dec (count s))) "ies")
    (re-find #"(s|x|ch|sh)$" s) (str s "es")
    :else (str s "s")))

(defn- normalize [[entity def]]
  (let [{:keys [schema path id-key expose?]} (if (map? def) def {:schema def})]
    [entity {:name entity
             :form schema
             :path (or path (str "/" (pluralize (name entity))))
             :id-key id-key
             :expose? (not (false? expose?))}]))

(defn- collect-refs
  "Entity names referenced anywhere inside a schema form."
  [known form]
  (cond
    (keyword? form) (if (known form) #{form} #{})
    (vector? form)
    (let [[head & more] form]
      (cond
        (contains? #{:enum :=} head) #{}
        (= :map head) (into #{} (mapcat (fn [e] (when (vector? e) (collect-refs known (peek e))))) more)
        :else (into #{} (mapcat #(when (or (keyword? %) (vector? %)) (collect-refs known %))) more)))
    :else #{}))

(defn- qualify
  "Malli's :ref only resolves qualified keywords, so every entity is also
  registered as :golem.entity/<name>."
  [entity]
  (keyword "golem.entity" (name entity)))

(defn- rewrite-refs
  "[:ref :user] -> [:ref :golem.entity/user] for known entities."
  [known form]
  (walk/postwalk
   (fn [x]
     (if (and (vector? x) (= 2 (count x)) (= :ref (first x)) (contains? known (second x)))
       [:ref (qualify (second x))]
       x))
   form))

(defn- describe [reg known {:keys [name form id-key] :as e}]
  (let [schema (m/schema form {:registry reg})]
    (when-not (= :map (m/type schema))
      (throw (ex-info (str "Entity " name " must be a :map schema") {:entity name})))
    (let [entries (vec (for [[k props child] (m/children schema)]
                         {:key k :optional? (boolean (:optional props)) :type (m/type child)}))
          id-key (or id-key (->> entries (map :key) (filter #(= "id" (clojure.core/name %))) first))]
      (assoc e
             ;; no id => embedded value object, no routes or seeded records
             :expose? (and (:expose? e) (some? id-key))
             :schema schema
             :entries entries
             :id-key id-key
             :id-type (:type (some #(when (= id-key (:key %)) %) entries))
             :refs (disj (:refs e) name)))))

(defn build
  "defs: {entity-name schema-or-config}. Returns {:registry reg :entities {name entity}}."
  [defs]
  (let [normalized (into (array-map) (map normalize) defs)
        known (set (keys normalized))
        normalized (into (array-map)
                         (map (fn [[k e]]
                                [k (-> e
                                       (assoc :refs (collect-refs known (:form e)))
                                       (update :form #(rewrite-refs known %)))]))
                         normalized)
        forms (into {} (map (fn [[k e]] [k (:form e)])) normalized)
        reg (mr/composite-registry m/default-registry
                                   (merge forms (update-keys forms qualify)))]
    {:registry reg
     :entities (into (array-map) (map (fn [[k e]] [k (describe reg known e)])) normalized)}))

(defn load-file-defs [path]
  (edn/read-string (slurp path)))

(defn load-registry [path]
  (build (load-file-defs path)))
