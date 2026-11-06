(ns golem.hydrate
  "Fills in server-owned fields (ids, timestamps) before validation."
  (:require [golem.store :as store])
  (:import (java.time Instant)))

(def ^:private updated-names #{"updated_at" "updatedAt"})
(def ^:private created-names #{"created_at" "createdAt"})
(def ^:private numeric-types #{:int :double :number 'int? 'number?})

(defn- now-value [type]
  (if (numeric-types type) (System/currentTimeMillis) (str (Instant/now))))

(defn- new-id [db e]
  (if (contains? #{:int 'int?} (:id-type e))
    (store/next-int-id db e)
    (str (random-uuid))))

(defn hydrate
  "body: JSON-shaped map (keyword keys). id: forced id (PUT). existing: the
  JSON-encoded current record (PUT), used to keep created_at."
  [db e body {:keys [id existing]}]
  (reduce
   (fn [body {:keys [key type]}]
     (let [n (name key)
           absent? (not (contains? body key))]
       (cond
         (= key (:id-key e)) (cond id (assoc body key id)
                                   absent? (assoc body key (new-id db e))
                                   :else body)
         (and absent? (updated-names n)) (assoc body key (now-value type))
         (and absent? (created-names n)) (assoc body key (or (get existing key) (now-value type)))
         :else body)))
   body (:entries e)))
