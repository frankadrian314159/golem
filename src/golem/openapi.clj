(ns golem.openapi
  "Generates an OpenAPI 3.1 description of the served API from the registry."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [malli.core :as m]
            [malli.json-schema :as mjs]))

(def ^:private defs-path "#/components/schemas/")

(def ^:private scalar-types
  #{:string :int :double :number :boolean :uuid :keyword :enum :inst})

(defn- component-name
  "Component names may only contain [A-Za-z0-9._-]; malli's ref names can carry
  a namespace and a slash (golem.entity/user)."
  [s]
  (-> s
      (str/replace #"^golem\.entity(/|~1)" "")
      (str/replace #"[^A-Za-z0-9._-]" "_")))

(defn- fix-refs
  "Rewrites every {:$ref \"#/components/schemas/<raw>\"} to use a legal name."
  [x]
  (walk/postwalk
   (fn [v]
     (if (and (map? v) (string? (:$ref v)) (str/starts-with? (:$ref v) defs-path))
       (assoc v :$ref (str defs-path
                           (component-name (str/replace (subs (:$ref v) (count defs-path)) "~1" "/"))))
       v))
   x))

(defn- to-schema
  "Malli schema -> [json-schema definitions] with definitions already renamed."
  [schema]
  (let [{:keys [definitions] :as js} (mjs/transform schema {::mjs/definitions-path defs-path})]
    [(fix-refs (dissoc js :definitions))
     (into {} (map (fn [[k v]] [(component-name (str k)) (fix-refs v)])) definitions)]))

(defn- ref-to [n] {:$ref (str defs-path n)})

(def ^:private error-ref (ref-to "Error"))

(defn- json [schema] {"application/json" {:schema schema}})

(defn- error-response [desc] {:description desc :content (json error-ref)})

(defn- entity-paths [{:keys [name path id-key entries schema] :as e}]
  (let [n (clojure.core/name name)
        comp (ref-to n)
        id-entry (some #(when (= id-key (:key %)) %) entries)
        id-param {:name "id" :in "path" :required true
                  :schema (or (when id-entry
                                (let [child (some (fn [[k _ c]] (when (= k id-key) c)) (m/children schema))]
                                  (first (to-schema child))))
                              {:type "string"})}
        filters (for [[k _ child] (m/children schema)
                      :when (scalar-types (m/type child))]
                  {:name (clojure.core/name k) :in "query" :required false
                   :description "Equality filter"
                   :schema (first (to-schema child))})
        not-found {"404" (error-response "No such record")}
        invalid {"400" (error-response "Validation failed")}
        tags [n]
        cap (str/capitalize n)
        patch-schema (let [[js _] (to-schema schema)] (dissoc js :required))]
    {path
     {:get {:operationId (str "list" cap) :tags tags :summary (str "List " n " records")
            :parameters (into [{:name "limit" :in "query" :schema {:type "integer" :minimum 0}}
                               {:name "offset" :in "query" :schema {:type "integer" :minimum 0}}]
                              filters)
            :responses {"200" {:description "Records in creation order"
                               :content (json {:type "array" :items comp})}}}
      :post {:operationId (str "create" cap) :tags tags :summary (str "Create a " n)
             :requestBody {:required true :content (json comp)}
             :responses (merge {"201" {:description "Created"
                                       :headers {"Location" {:schema {:type "string"}}}
                                       :content (json comp)}
                                "409" (error-response "A record with that id already exists")}
                               invalid)}}
     (str path "/{id}")
     {:parameters [id-param]
      :get {:operationId (str "get" cap) :tags tags :summary (str "Fetch a " n)
            :responses (merge {"200" {:description "The record" :content (json comp)}} not-found)}
      :put {:operationId (str "replace" cap) :tags tags :summary (str "Replace a " n)
            :requestBody {:required true :content (json comp)}
            :responses (merge {"200" {:description "The replaced record" :content (json comp)}}
                              invalid not-found)}
      :patch {:operationId (str "patch" cap) :tags tags
              :summary (str "Partially update a " n " (objects merge deeply)")
              :requestBody {:required true :content (json patch-schema)}
              :responses (merge {"200" {:description "The updated record" :content (json comp)}}
                                invalid not-found)}
      :delete {:operationId (str "delete" cap) :tags tags :summary (str "Delete a " n)
               :responses (merge {"204" {:description "Deleted"}} not-found)}}}))

(defn openapi
  "OpenAPI 3.1 document (a map) for the entities in reg. opts: :title, :version,
  :reset? (include POST /_reset)."
  [{:keys [entities]} {:keys [title version reset?] :or {title "golem mock API" version "1.0.0"}}]
  (let [exposed (filter :expose? (vals entities))
        schemas (reduce (fn [acc e]
                          (let [[js defs] (to-schema (:schema e))]
                            (merge acc defs {(clojure.core/name (:name e)) js})))
                        {} exposed)]
    {:openapi "3.1.0"
     :info {:title title :version version}
     :paths (merge (into (sorted-map) (mapcat entity-paths) exposed)
                   {"/_schemas" {:get {:operationId "describeSchemas" :tags ["golem"]
                                       :summary "Describe the entities"
                                       :responses {"200" {:description "One object per entity"
                                                          :content (json {:type "array" :items {:type "object"}})}}}}
                    "/_openapi" {:get {:operationId "getOpenApi" :tags ["golem"]
                                       :summary "This document"
                                       :responses {"200" {:description "OpenAPI document"
                                                          :content (json {:type "object"})}}}}}
                   (when reset?
                     {"/_reset" {:post {:operationId "resetData" :tags ["golem"]
                                        :summary "Discard all changes and regenerate the seed data"
                                        :parameters [{:name "seed" :in "query" :schema {:type "integer"}}]
                                        :responses {"200" {:description "Data regenerated"
                                                           :content (json {:type "object"})}
                                                    "400" (error-response "Invalid seed")}}}}))
     :components {:schemas (assoc schemas
                                  "Error" {:type "object" :required ["error"]
                                           :properties {:error {:type "string"}
                                                        :errors {:type "object"}}})}}))
