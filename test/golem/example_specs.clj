(ns golem.example-specs
  (:require [clojure.spec.alpha :as s]))

;; clojure.spec fixtures
(s/def ::street string?)
(s/def ::city string?)
(s/def ::address (s/keys :req-un [::street ::city]))

(s/def ::id uuid?)
(s/def ::name (s/and string? seq))
(s/def ::email (s/and string? #(re-matches #"[a-z]+@example\.com" %)))
(s/def ::age (s/int-in 18 100))
(s/def ::tags (s/coll-of string? :kind vector? :max-count 3))
(s/def ::role #{:admin :member})
(s/def ::nickname (s/nilable string?))
(s/def ::user (s/keys :req-un [::id ::name ::email ::address ::tags ::role]
                      :opt-un [::age ::nickname]))

;; Malli fixture, loadable as "golem.example-specs/schemas"
(def schemas
  {:account [:map [:id :int] [:owner :string]]})
