;; Shop API defined with clojure.spec: customers, products and orders.
;; Orders embed line items (no id, so they get no endpoints of their own).
;; Foreign keys are limited to 1..5 so they point at real records when the
;; server runs with the default --count 5.
(ns shop.specs
  (:require [clojure.spec.alpha :as s]))

;; embedded value objects
(s/def ::street string?)
(s/def ::city string?)
(s/def ::address (s/keys :req-un [::street ::city]))

(s/def ::product_id (s/int-in 1 6))
(s/def ::quantity (s/int-in 1 10))
(s/def ::line-item (s/keys :req-un [::product_id ::quantity]))

;; shared fields
(s/def ::id pos-int?)
(s/def ::created_at string?)

;; customer
(s/def ::name (s/and string? seq))
(s/def ::email string?)
(s/def ::vip boolean?)
(s/def ::customer (s/keys :req-un [::id ::name ::email ::address ::created_at]
                          :opt-un [::vip]))

;; product
(s/def ::sku (s/and string? #(re-matches #"[A-Z]{3}-\d{4}" %)))
(s/def ::title (s/and string? seq))
(s/def ::price (s/double-in :min 1.0 :max 500.0))
(s/def ::category #{"books" "toys" "garden"})
(s/def ::in_stock boolean?)
(s/def ::product (s/keys :req-un [::id ::sku ::title ::price ::category ::in_stock]))

;; order
(s/def ::customer_id (s/int-in 1 6))
(s/def ::items (s/coll-of ::line-item :min-count 1 :max-count 3))
(s/def ::status #{"pending" "paid" "shipped"})
(s/def ::order (s/keys :req-un [::id ::customer_id ::items ::status ::created_at]))
