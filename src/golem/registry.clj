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
                         (cond-> {:key k :optional? (boolean (:optional props)) :type (m/type child)}
                                   (contains? props :golem/ref) (assoc :ref (:golem/ref props)))))
          id-key (or id-key (->> entries (map :key) (filter #(= "id" (clojure.core/name %))) first))]
      (assoc e
             ;; no id => embedded value object, no routes or seeded records
             :expose? (and (:expose? e) (some? id-key))
             :schema schema
             :entries entries
             :id-key id-key
             :id-type (:type (some #(when (= id-key (:key %)) %) entries))
             :refs (disj (:refs e) name)))))

;; ---- foreign keys ----------------------------------------------------------

(defn- own-fks
  "Field name -> target entity for this entity's own fields. A field is a foreign
  key when it carries {:golem/ref :entity} (false opts out), or when it is named
  <entity>_id / <entity>Id for an exposed entity whose id has the same type."
  [entities {:keys [name entries]}]
  (into {}
        (keep (fn [{:keys [key ref type]}]
                (let [fname (clojure.core/name key)
                      base (second (re-matches #"(.+?)(?:_id|Id)" fname))
                      auto (when base
                             (let [t (get entities (keyword base))]
                               (when (and t (:expose? t) (not= name (:name t))
                                          (= type (:id-type t)))
                                 (:name t))))
                      target (cond (false? ref) nil
                                   (keyword? ref) (when (get-in entities [ref :expose?]) ref)
                                   :else auto)]
                  (when (and target (not= target name)) [fname target]))))
        entries))

(defn- add-fks
  "Adds :fks {field-name target-entity} to every entity. Foreign keys of embedded
  entities (e.g. line items inside an order) count for the entities that embed them."
  [entities]
  (let [own (into {} (map (fn [[k e]] [k (own-fks entities e)])) entities)
        closure (fn closure [seen k]
                  (if (seen k)
                    {}
                    (apply merge (get own k)
                           (map #(closure (conj seen k) %) (:refs (get entities k))))))]
    (into (array-map)
          (map (fn [[k e]] [k (assoc e :fks (closure #{} k))]))
          entities)))

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
     :entities (add-fks (into (array-map) (map (fn [[k e]] [k (describe reg known e)])) normalized))}))

(defn load-file-defs [path]
  (edn/read-string (slurp path)))

(defn load-registry [path]
  (build (load-file-defs path)))
