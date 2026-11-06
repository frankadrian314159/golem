(ns golem.import
  "Import schemas from Malli (edn / clj file / ns var) or clojure.spec.
  Both produce {entity-name schema-or-config}, the input of golem.registry/build."
  (:require [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [malli.core :as m]))

;; ---- Malli ---------------------------------------------------------------

(defn- ->form [v]
  (cond
    (m/schema? v) (m/form v)
    (and (map? v) (m/schema? (:schema v))) (update v :schema m/form)
    :else v))

(defn- normalize-defs
  "Accepts Schema objects and qualified entity keys (:app/user -> :user)."
  [defs]
  (let [rename (into {} (for [k (keys defs) :when (qualified-keyword? k)] [k (keyword (name k))]))]
    (into (array-map)
          (map (fn [[k v]] [(get rename k k) (walk/postwalk #(get rename % %) (->form v))]))
          defs)))

(defn malli-defs
  "source is one of:
    path/to/schemas.edn         an EDN map {entity schema}
    path/to/schemas.clj(c)      a file whose last form evaluates to that map
    my.ns/var                   a var holding the map
    my.ns                       a namespace with a var named `schemas`"
  [source]
  (let [v (cond
            (re-find #"\.edn$" source) (edn/read-string (slurp source))
            (re-find #"\.cljc?$" source) (load-file source)
            (str/includes? source "/") (some-> (requiring-resolve (symbol source)) deref)
            :else (do (require (symbol source))
                      (some-> (ns-resolve (symbol source) 'schemas) deref)))]
    (when-not (map? v)
      (throw (ex-info (str "Malli source did not yield a map of entities: " source) {:source source})))
    (normalize-defs v)))

;; ---- clojure.spec ----------------------------------------------------------

(def ^:dynamic *warnings* nil)

(defn- warn! [msg] (when *warnings* (swap! *warnings* conj msg)))

(def ^:private preds
  {'clojure.core/string? :string
   'clojure.core/int? :int
   'clojure.core/integer? :int
   'clojure.core/pos-int? [:int {:min 1}]
   'clojure.core/nat-int? [:int {:min 0}]
   'clojure.core/neg-int? [:int {:max -1}]
   'clojure.core/double? [:double {:gen/infinite? false :gen/NaN? false}]
   'clojure.core/float? [:double {:gen/infinite? false :gen/NaN? false}]
   'clojure.core/number? [:double {:gen/infinite? false :gen/NaN? false}]
   'clojure.core/boolean? :boolean
   'clojure.core/keyword? :keyword
   'clojure.core/symbol? :symbol
   'clojure.core/uuid? :uuid
   'clojure.core/inst? :inst
   'clojure.core/any? :any
   'clojure.core/nil? :nil
   'clojure.core/some? :some})

(declare convert)

(defn- bound-marker
  "`(fn [%] (<= % n))` / `(fn [%] (<= n %))` (as expanded by s/double-in) -> [::bound :max|:min n];
  NaN/infinity guards -> [::bound :finite]. nil for anything else."
  [form]
  (when (and (seq? form) (= 'clojure.core/fn (first form)))
    (let [[_ [p] body] form]
      (when (seq? body)
        (let [[op a b] body]
          (cond
            (and (= op 'clojure.core/<=) (= a p) (number? b)) [::bound :max b]
            (and (= op 'clojure.core/<=) (= b p) (number? a)) [::bound :min a]
            (and (= op 'clojure.core/not) (seq? a)
                 (contains? '#{Double/isInfinite Double/isNaN} (first a))) [::bound :finite]))))))

(defn- apply-bounds [parts markers]
  (reduce
   (fn [parts [_ k n]]
     (if (= :finite k)
       parts
       (let [numeric? #(contains? #{:int :double} (if (vector? %) (first %) %))
             i (first (keep-indexed #(when (numeric? %2) %1) parts))]
         (if i
           (update (vec parts) i
                   (fn [x] (if (vector? x)
                             (update x 1 (fnil assoc {}) k n)
                             [x {k n}])))
           (do (warn! (str "bound " k " " n " has no numeric spec to apply to, ignored")) parts)))))
   (vec parts) markers))

(defn- opts-map [args] (apply hash-map args))

(defn- count-props [{:keys [min-count max-count count]}]
  (cond-> {}
    min-count (assoc :min min-count)
    max-count (assoc :max max-count)
    count (assoc :min count :max count)))

(defn- keys->malli [ents args]
  (let [{:keys [req opt req-un opt-un]} (opts-map args)
        field (fn [k out optional?]
                (if-not (keyword? k)
                  (do (warn! (str "ignoring non-keyword key spec " (pr-str k) " in s/keys")) nil)
                  (let [sch (convert ents k)]
                    (if optional? [out {:optional true} sch] [out sch]))))
        un #(keyword (name %))]
    (into [:map]
          (remove nil?)
          (concat (map #(field % % false) req)
                  (map #(when (keyword? %) (field % (un %) false)) req-un)
                  (map #(field % % true) opt)
                  (map #(when (keyword? %) (field % (un %) true)) opt-un)))))

(defn- convert-seq [ents [op & args :as form]]
  (case op
    clojure.spec.alpha/keys (keys->malli ents args)
    clojure.spec.alpha/and (let [seq? #{'clojure.core/seq 'clojure.core/not-empty}
                                 nonempty? (some seq? args)
                                 markers (keep bound-marker args)
                                 parts (->> args
                                            (remove seq?)
                                            (remove bound-marker)
                                            (map #(convert ents %))
                                            (map #(if (and nonempty? (= :string %)) [:string {:min 1}] %))
                                            (remove #{:any}))
                                 ;; drop a bare :int/:string when a refined [:int ..]/[:string ..]/[:re ..] is present
                                 refined (set (keep #(when (vector? %) (first %)) parts))
                                 parts (remove #(or (and (keyword? %) (refined %))
                                                    (and (= :string %) (refined :re)))
                                               parts)]
                             (let [parts (apply-bounds parts markers)]
                               (if (= 1 (count parts)) (first parts) (into [:and] parts))))
    clojure.spec.alpha/or (into [:orn] (map (fn [[t f]] [t (convert ents f)])) (partition 2 args))
    clojure.spec.alpha/nilable [:maybe (convert ents (first args))]
    (clojure.spec.alpha/coll-of clojure.spec.alpha/every)
    (let [[x & more] args
          o (opts-map more)
          kind (if (= 'clojure.core/set? (:kind o)) :set :vector)
          props (count-props o)]
      (if (seq props) [kind props (convert ents x)] [kind (convert ents x)]))
    clojure.spec.alpha/map-of [:map-of (convert ents (first args)) (convert ents (second args))]
    clojure.spec.alpha/int-in (let [[a b] args] [:int {:min a :max (dec b)}])
    clojure.spec.alpha/double-in (let [{:keys [min max]} (opts-map args)]
                                   [:double (cond-> {:gen/infinite? false :gen/NaN? false}
                                              min (assoc :min min) max (assoc :max max))])
    clojure.spec.alpha/spec (convert ents (first args))
    clojure.core/fn (let [[[p] body] args]
                      (cond
                        (and (seq? body) (= 'clojure.spec.alpha/int-in-range? (first body)))
                        (let [[_ a b] body] [:int {:min a :max (dec b)}])

                        :else
                        (if (and (seq? body) (= 'clojure.core/re-matches (first body))
                               (instance? java.util.regex.Pattern (second body)) (= p (last body)))
                        [:re (second body)]
                        (do (warn! (str "unsupported predicate " (pr-str form) ", treated as :any")) :any))))
    (do (warn! (str "unsupported spec form " (pr-str form) ", treated as :any")) :any)))

(defn- convert [ents form]
  (cond
    (and (keyword? form) (ents form)) (ents form)
    (keyword? form) (if (s/get-spec form)
                      (convert ents (s/form form))
                      (do (warn! (str "unregistered spec " form ", treated as :any")) :any))
    (set? form) (into [:enum] form)
    (symbol? form) (or (preds form)
                       (do (warn! (str "unsupported predicate " form ", treated as :any")) :any))
    (seq? form) (convert-seq ents form)
    :else (do (warn! (str "unsupported spec form " (pr-str form))) :any)))

(defn spec-defs
  "source: a namespace name, or a .clj(c) file starting with an (ns ...) form.
  Every s/keys spec registered in that namespace becomes an entity; other
  entities are referenced by name where one s/keys spec uses another.
  Returns the defs with {:warnings [...]} metadata for anything approximated."
  [source]
  (let [file? (re-find #"\.cljc?$" source)
        ns-sym (if file?
                 (do (load-file source) (second (read-string (slurp source))))
                 (do (require (symbol source)) (symbol source)))
        keys-spec? (fn [k] (let [f (s/form k)] (and (seq? f) (= 'clojure.spec.alpha/keys (first f)))))
        entity-keys (->> (keys (s/registry))
                         (filter #(and (qualified-keyword? %) (= (str ns-sym) (namespace %)) (s/get-spec %)))
                         (filter keys-spec?)
                         sort)
        ents (into {} (map (fn [k] [k (keyword (name k))])) entity-keys)
        warnings (atom [])]
    (when (empty? entity-keys)
      (throw (ex-info (str "No s/keys specs registered in " ns-sym) {:source source})))
    (-> (binding [*warnings* warnings]
          (into (array-map)
                (map (fn [k] [(ents k) (keys->malli ents (rest (s/form k)))]))
                entity-keys))
        (with-meta {:warnings (vec (distinct @warnings))}))))

;; ---- combined ----------------------------------------------------------------

(defn load-defs
  "{:schemas malli-source :specs spec-source} (either or both) -> defs."
  [{:keys [schemas specs]}]
  (let [a (when schemas (malli-defs schemas))
        b (when specs (spec-defs specs))]
    (when-not (or a b)
      (throw (ex-info "Provide --schemas (Malli) and/or --specs (clojure.spec)" {})))
    (with-meta (merge a b) (meta b))))
