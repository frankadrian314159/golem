(ns golem.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [golem.app :as app]
            [golem.gen :as gen]
            [golem.import]
            [golem.registry :as registry]
            [golem.store :as store]
            [jsonista.core :as j]))

(def reg (registry/load-registry "src/schemas.edn"))

(defn- fresh-handler [seed]
  (let [db (store/create-db)]
    (app/seed-db! db reg {:seed seed :count 3 :size 10})
    (app/make-handler reg db)))

(defn- call [h method uri & [body]]
  (let [resp (h {:request-method method :uri uri :headers {}
                 :body (when body (java.io.ByteArrayInputStream. (.getBytes (j/write-value-as-string body))))})]
    (assoc resp :json (some-> (:body resp) (j/read-value (j/object-mapper {:decode-key-fn keyword}))))))

(deftest registry-test
  (is (= #{:address} (get-in reg [:entities :user :refs])))
  (is (= :id (get-in reg [:entities :post :id-key]))))

(deftest seeding-is-deterministic
  (let [a (gen/generate-items (get-in reg [:entities :user]) {:seed 42 :count 3})
        b (gen/generate-items (get-in reg [:entities :user]) {:seed 42 :count 3})]
    (is (= a b))
    (is (every? #(re-matches #".+@example\.com" (:email %)) a))))

(deftest crud-flow
  (let [h (fresh-handler 7)
        users (:json (call h :get "/users"))]
    (is (= 3 (count users)))
    (let [id (:id (first users))]
      (is (= 200 (:status (call h :get (str "/users/" id)))))
      (is (= 404 (:status (call h :get "/users/nope"))))
      (let [r (call h :patch (str "/users/" id) {:address {:city "Paris"}})]
        (is (= 200 (:status r)))
        (is (= "Paris" (get-in r [:json :address :city])))
        (is (some? (get-in r [:json :address :street]))))
      (is (= 400 (:status (call h :patch (str "/users/" id) {:age 5}))))
      (is (= 204 (:status (call h :delete (str "/users/" id)))))
      (is (= 404 (:status (call h :get (str "/users/" id))))))))

(deftest create-validates-and-hydrates
  (let [h (fresh-handler 1)
        bad (call h :post "/users" {:name "x"})
        good (call h :post "/users" {:name "Ada" :email "ada@example.com"
                                     :address {:street "1 Main" :city "X" :zip "12345"}})]
    (is (= 400 (:status bad)))
    (is (map? (get-in bad [:json :errors])))
    (is (= 201 (:status good)))
    (is (get-in good [:json :id]))
    (is (get-in good [:json :created_at]))
    (let [id (get-in good [:json :id])
          put (call h :put (str "/users/" id) {:name "Ada L" :email "ada@example.com"
                                               :address {:street "1 Main" :city "X" :zip "12345"}})]
      (is (= 200 (:status put)))
      (is (= (get-in good [:json :created_at]) (get-in put [:json :created_at]))))
    (testing "int ids auto-increment"
      (let [p (call h :post "/posts" {:title "t" :body "b" :tags [] :published true
                                      :author_id "00000000-0000-0000-0000-000000000001"})]
        (is (= 4 (get-in p [:json :id])))))))

(deftest filtering
  (let [h (fresh-handler 3)
        first-user (first (:json (call h :get "/users")))
        hits (:json (call h :get (str "/users?address.city=" (java.net.URLEncoder/encode (get-in first-user [:address :city]) "UTF-8"))))]
    (is (seq hits))))

(deftest import-malli-from-var
  (let [defs (golem.import/malli-defs "golem.example-specs/schemas")
        r (registry/build defs)]
    (is (= [:account] (keys (:entities r))))))

(deftest import-clojure-spec
  (let [defs (golem.import/spec-defs "golem.example-specs")
        r (registry/build defs)
        user (get-in r [:entities :user])]
    (is (= #{:address :user} (set (keys (:entities r)))))
    (is (= #{:address} (:refs user)))
    (let [items (gen/generate-items user {:seed 5 :count 4})]
      (is (= 4 (count items)))
      (is (every? #(re-matches #"[a-z]+@example\.com" (:email %)) items))
      (is (every? #(contains? #{:admin :member} (:role %)) items)))))

(deftest spec-refinements
  (let [user (get-in (registry/build (golem.import/spec-defs "golem.example-specs")) [:entities :user])
        items (gen/generate-items user {:seed 9 :count 10})]
    (is (every? #(<= 18 (or (:age %) 18) 99) items))
    (is (every? #(seq (:name %)) items))
    (is (empty? (:warnings (meta (golem.import/spec-defs "golem.example-specs")))))))

(defn- example-handler [defs]
  (let [r (registry/build defs) db (store/create-db)]
    (app/seed-db! db r {:seed 42 :count 5 :size 10})
    [r (app/make-handler r db)]))

(deftest blog-example
  (let [[r h] (example-handler (golem.import/malli-defs "examples/blog/schemas.edn"))]
    (is (= #{:author :post :comment} (set (keys (:entities r)))))
    (doseq [path ["/authors" "/posts" "/comments"]]
      (is (= 5 (count (:json (call h :get path)))) path))
    (let [posts (:json (call h :get "/posts"))]
      (is (every? #(<= 1 (:author_id %) 5) posts))
      (is (every? #{"draft" "published" "archived"} (map :status posts))))
    (let [created (call h :post "/posts" {:author_id 1 :title "Hi" :body "b" :status "draft"
                                          :tags [] :stats {:views 0 :likes 0}})
          id (get-in created [:json :id])
          patched (call h :patch (str "/posts/" id) {:stats {:views 10}})]
      (is (= 6 id))
      (is (= {:views 10 :likes 0} (get-in patched [:json :stats]))))))

(deftest shop-example
  (let [defs (golem.import/spec-defs "examples/shop/shop_specs.clj")
        [r h] (example-handler defs)]
    (is (empty? (:warnings (meta defs))))
    (is (= #{:customer :product :order} (set (map :name (filter :expose? (vals (:entities r)))))))
    (let [products (:json (call h :get "/products"))
          orders (:json (call h :get "/orders"))]
      (is (= 5 (count products)))
      (is (every? #(<= 1.0 (:price %) 500.0) products))
      (is (every? #(re-matches #"[A-Z]{3}-\d{4}" (:sku %)) products))
      (is (every? #(<= 1 (count (:items %)) 3) orders)))
    (is (= 400 (:status (call h :post "/orders" {:customer_id 9 :items [] :status "lost"}))))
    (is (= 201 (:status (call h :post "/orders" {:customer_id 2 :status "pending"
                                                 :items [{:product_id 3 :quantity 2}]}))))))

(deftest recursive-entities
  (let [defs {:comment [:map [:id :int] [:text :string]
                        [:replies {:optional true} [:vector [:ref :comment]]]]}
        [r h] (example-handler defs)]
    (is (= #{} (get-in r [:entities :comment :refs])))
    (is (= 5 (count (:json (call h :get "/comments")))))
    (let [resp (call h :post "/comments" {:text "a" :replies [{:id 9 :text "b" :replies [{:id 10 :text "c"}]}]})]
      (is (= 201 (:status resp)))
      (is (= "c" (get-in resp [:json :replies 0 :replies 0 :text]))))
    (is (= 400 (:status (call h :post "/comments" {:text "a" :replies [{:text 1}]}))))))
