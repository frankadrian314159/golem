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
  (let [[path qs] (clojure.string/split uri #"\?" 2)
        resp (h {:request-method method :uri path :query-string qs :headers {}
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

;; ---- foreign keys -------------------------------------------------------------

(deftest foreign-keys-detected
  (is (= {"author_id" :user} (get-in reg [:entities :post :fks])))
  (let [r (registry/build {:author [:map [:id :int] [:name :string]]
                           :a [:map [:id :int] [:writer_id {:golem/ref :author} :int] [:author_id {:golem/ref false} :int]]
                           :b [:map [:id :int] [:authorId :int] [:author_id :uuid]]})]
    (is (= {"writer_id" :author} (get-in r [:entities :a :fks])) "explicit ref wins, false opts out")
    (is (= {"authorId" :author} (get-in r [:entities :b :fks])) "type mismatch is not auto-detected"))
  (testing "embedded entities contribute their foreign keys"
    (let [r (registry/build {:thing [:map [:id :int]]
                             :line [:map [:thing_id :int]]
                             :box [:map [:id :int] [:lines [:vector :line]]]})]
      (is (= {"thing_id" :thing} (get-in r [:entities :box :fks]))))))

(deftest seeded-foreign-keys-point-at-real-records
  (doseq [seed [1 2 3 42]]
    (let [db (store/create-db)]
      (app/seed-db! db reg {:seed seed :count 6 :size 10})
      (let [user-ids (set (map :id (vals (get-in @db [:user :items]))))
            posts (vals (get-in @db [:post :items]))]
        (is (= 6 (count posts)))
        (is (every? #(contains? user-ids (:author_id %)) posts) (str "seed " seed))))))

(deftest foreign-key-seeding-is-deterministic
  (let [snap (fn [] (let [db (store/create-db)] (app/seed-db! db reg {:seed 5 :count 4}) @db))]
    (is (= (snap) (snap)))))

(deftest nested-foreign-keys-and-dependency-order
  (let [defs (golem.import/spec-defs "examples/shop/shop_specs.clj")
        r (registry/build defs)
        db (store/create-db)]
    (is (= [:product :customer] (take 2 (sort-by {:product 0 :customer 1 :order 2} (app/seed-order (:entities r))))))
    (is (< (.indexOf (vec (app/seed-order (:entities r))) :customer)
           (.indexOf (vec (app/seed-order (:entities r))) :order)))
    (app/seed-db! db r {:seed 3 :count 4})
    (let [pids (set (map :id (vals (get-in @db [:product :items]))))
          cids (set (map :id (vals (get-in @db [:customer :items]))))
          orders (vals (get-in @db [:order :items]))]
      (is (every? #(cids (:customer_id %)) orders))
      (is (every? (fn [o] (every? #(pids (:product_id %)) (:items o))) orders)))))

(deftest mutually-dependent-entities-still-seed
  (let [r (registry/build {:a [:map [:id :int] [:b_id {:golem/ref :b} :int]]
                           :b [:map [:id :int] [:a_id {:golem/ref :a} :int]]})
        db (store/create-db)]
    (app/seed-db! db r {:seed 1 :count 3})
    (is (= 3 (count (get-in @db [:a :items]))))
    (is (= 3 (count (get-in @db [:b :items]))))))

;; ---- reset and persistence ----------------------------------------------------

(deftest reset-endpoint
  (let [db (store/create-db)
        opts {:seed 7 :count 3 :size 10}
        _ (app/seed-db! db reg opts)
        h (app/make-handler reg db opts)
        before (:json (call h :get "/users"))]
    (call h :delete (str "/users/" (:id (first before))))
    (call h :post "/users" {:name "Ada" :email "a@example.com" :address {:street "1" :city "X" :zip "12345"}})
    (is (not= before (:json (call h :get "/users"))))
    (let [r (call h :post "/_reset")]
      (is (= 200 (:status r)))
      (is (= 7 (get-in r [:json :seed]))))
    (is (= before (:json (call h :get "/users"))) "same seed restores the original data")
    (let [r (call h :post "/_reset?seed=8")]
      (is (= 8 (get-in r [:json :seed])))
      (is (not= before (:json (call h :get "/users")))))
    (is (= 400 (:status (call h :post "/_reset?seed=abc"))))
    (is (= 404 (:status (call (fresh-handler 1) :post "/_reset"))) "no /_reset without seed options")))

(deftest persistence-round-trip
  (let [f (java.io.File/createTempFile "golem" ".edn")
        path (.getPath f)
        opts {:seed 9 :count 3}
        db (store/create-db)]
    (try
      (app/seed-db! db reg opts)
      (store/autosave! db path)
      (let [h (app/make-handler reg db opts)
            created (:json (call h :post "/users" {:name "Ada" :email "a@example.com"
                                                   :address {:street "1" :city "X" :zip "12345"}}))
            db2 (store/create-db)]
        (is (true? (store/load! db2 path)))
        (is (= @db @db2) "every change reached the file, uuids included")
        (let [h2 (app/make-handler reg db2 opts)]
          (is (= created (:json (call h2 :get (str "/users/" (:id created))))))
          (is (= 4 (count (:json (call h2 :get "/users")))))))
      (is (false? (boolean (store/load! (store/create-db) (str path ".missing")))))
      (finally (.delete f) (.delete (java.io.File. (str path ".tmp")))))))

;; ---- OpenAPI ------------------------------------------------------------------

(deftest openapi-document
  (let [h (fresh-handler 1)
        r (call h :get "/_openapi")
        doc (:json r)]
    (is (= 200 (:status r)))
    (is (= "3.1.0" (:openapi doc)))
    (is (= (set (map keyword ["/users" "/users/{id}" "/posts" "/posts/{id}" "/_schemas" "/_openapi"])) (set (keys (:paths doc)))))
    (is (= #{:get :post} (set (keys (get-in doc [:paths (keyword "/users")])))))
    (is (= #{:parameters :get :put :patch :delete} (set (keys (get-in doc [:paths (keyword "/users/{id}")])))))
    (is (= "#/components/schemas/user" (get-in doc [:paths (keyword "/users") :post :requestBody :content :application/json :schema :$ref])))
    (let [user (get-in doc [:components :schemas :user])]
      (is (= "object" (:type user)))
      (is (= "uuid" (get-in user [:properties :id :format])))
      (is (some #{"name"} (:required user))))
    (is (not (contains? (get-in doc [:components :schemas :user]) :definitions)))
    (is (nil? (get-in doc [:paths (keyword "/_reset")])))
    (testing "patch bodies have no required fields"
      (is (nil? (:required (get-in doc [:paths (keyword "/users/{id}") :patch :requestBody :content :application/json :schema])))))
    (testing "every $ref resolves and component names are legal"
      (let [names (set (map name (keys (get-in doc [:components :schemas]))))
            refs (atom #{})]
        (clojure.walk/postwalk (fn [x] (when (and (map? x) (string? (:$ref x))) (swap! refs conj (:$ref x))) x) doc)
        (is (seq @refs))
        (is (every? #(names (subs % (count "#/components/schemas/"))) @refs))
        (is (every? #(re-matches #"[A-Za-z0-9._-]+" %) names)))))
  (testing "entity references and recursion become component refs, /_reset appears with seed options"
    (let [r (registry/build {:comment [:map [:id :int] [:text :string]
                                       [:replies {:optional true} [:vector [:ref :comment]]]]})
          doc (golem.openapi/openapi r {:reset? true})
          s (get-in doc [:components :schemas "comment"])]
      (is (= "#/components/schemas/comment" (get-in s [:properties :replies :items :$ref])))
      (is (contains? (:paths doc) "/_reset")))))

;; ---- bug fixes ------------------------------------------------------------------

(def ^:private ada {:name "Ada" :email "a@example.com" :address {:street "1" :city "X" :zip "12345"}})

(deftest integer-ids-are-allocated-atomically
  (let [db (store/create-db)
        post (get-in reg [:entities :post])
        ids (->> (range 200) (pmap (fn [_] (store/next-int-id db post))) doall)]
    (is (= 200 (count (set ids))) "concurrent callers never share an id"))
  (let [h (fresh-handler 1)
        ids (->> (range 40)
                 (pmap (fn [_] (get-in (call h :post "/posts" {:title "t" :body "b" :tags [] :published true
                                                               :author_id "00000000-0000-0000-0000-000000000001"})
                                       [:json :id])))
                 doall)]
    (is (= 40 (count (set ids))))
    (is (every? some? ids) "no 409s under concurrent POSTs")
    (testing "ids are not reused after a delete"
      (let [top (apply max ids)]
        (call h :delete (str "/posts/" top))
        (is (> (get-in (call h :post "/posts" {:title "t" :body "b" :tags [] :published true
                                               :author_id "00000000-0000-0000-0000-000000000001"})
                       [:json :id])
               top))))))

(deftest bad-query-parameters-are-400s
  (let [h (fresh-handler 3)]
    (doseq [qs ["limit=abc" "offset=x" "limit=-1" "limit=1&limit=2" "nonsense=1" "address.nope=1"]]
      (is (= 400 (:status (call h :get (str "/users?" qs)))) qs))
    (is (re-find #"nonsense" (get-in (call h :get "/users?nonsense=1") [:json :error])))
    (is (= 200 (:status (call h :get "/users?limit=1&offset=1&address.city=zzz"))))
    (testing "a repeated filter matches either value"
      (let [users (:json (call h :get "/users"))
            [a b] (map :id users)]
        (is (= 2 (count (:json (call h :get (str "/users?id=" a "&id=" b))))))))))

(deftest patch-is-a-merge-patch
  (let [h (fresh-handler 3)
        db (store/create-db)
        _ (app/seed-db! db reg {:seed 3 :count 3})
        h (app/make-handler reg db)
        id (:id (first (:json (call h :get "/users"))))]
    (testing "null on a required field is stored when removal would be invalid"
      (is (= 400 (:status (call h :patch (str "/users/" id) {:name nil})))))
    (testing "null deletes an optional field"
      (is (= 200 (:status (call h :patch (str "/users/" id) {:age 30}))))
      (let [r (call h :patch (str "/users/" id) {:age nil})]
        (is (= 200 (:status r)))
        (is (not (contains? (:json r) :age)))))
    (testing "arrays and scalars replace, nested maps merge"
      (let [r (call h :patch (str "/users/" id) {:address {:city "Rome"}})]
        (is (= "Rome" (get-in r [:json :address :city])))
        (is (some? (get-in r [:json :address :zip])))))))

(deftest strict-mode-rejects-unknown-fields
  (let [db (store/create-db)
        _ (app/seed-db! db reg {:seed 3 :count 3})
        lax (app/make-handler reg db)
        strict (app/make-handler reg db nil {:strict? true})
        id (:id (first (:json (call lax :get "/users"))))]
    (is (= 201 (:status (call lax :post "/users" (assoc ada :bogus 1)))) "dropped silently by default")
    (let [r (call strict :post "/users" (assoc ada :bogus 1 :address (assoc (:address ada) :planet "Mars")))]
      (is (= 400 (:status r)))
      (is (= ["disallowed key"] (get-in r [:json :errors :bogus])))
      (is (= ["disallowed key"] (get-in r [:json :errors :address :planet]))))
    (is (= 400 (:status (call strict :patch (str "/users/" id) {:bogus 1}))))
    (is (= 201 (:status (call strict :post "/users" ada))))))

;; ---- simulation -------------------------------------------------------------------

(defn- sim-handler [opts]
  (let [db (store/create-db)]
    (app/seed-db! db reg {:seed 3 :count 3})
    (app/make-handler reg db {:seed 3 :count 3} opts)))

(defn- call-h [h method uri headers]
  (let [[path qs] (clojure.string/split uri #"\?" 2)]
    (h {:request-method method :uri path :query-string qs :headers headers})))

(deftest parse-delay-test
  (is (= [300 300] (app/parse-delay "300")))
  (is (= [200 800] (app/parse-delay "200-800")))
  (is (nil? (app/parse-delay nil)))
  (is (thrown? Exception (app/parse-delay "fast")))
  (is (thrown? Exception (app/parse-delay "9-1"))))

(deftest forced-status
  (let [h (sim-handler nil)]
    (is (= 503 (:status (call-h h :get "/users" {"x-golem-status" "503"}))))
    (is (= 429 (:status (call-h h :get "/users?_status=429" {}))))
    (is (= 400 (:status (call-h h :get "/users?_status=abc" {}))))
    (is (= 400 (:status (call-h h :get "/users" {"x-golem-status" "200"}))))
    (is (= 200 (:status (call-h h :get "/users" {}))) "no header, no failure")
    (is (= 200 (:status (call-h h :get "/_schemas?_status=500" {}))) "meta routes are exempt")))

(deftest error-rate
  (let [h (sim-handler {:error-rate 1.0})]
    (is (every? #(<= 500 % 504) (repeatedly 5 #(:status (call-h h :get "/users" {}))))))
  (is (= 200 (:status (call-h (sim-handler {:error-rate 0.0}) :get "/users" {}))))
  (let [statuses #(let [h (sim-handler {:error-rate 0.5})] (mapv (fn [_] (:status (call-h h :get "/users" {}))) (range 20)))]
    (is (= (statuses) (statuses)) "failures are repeatable for the same seed")
    (is (< 0 (count (filter #(= 200 %) (statuses))) 20))))

(deftest fake-auth
  (let [any (sim-handler {:auth true})
        tok (sim-handler {:auth "s3cret"})]
    (is (= 401 (:status (call-h any :get "/users" {}))))
    (is (= "Bearer" (get-in (call-h any :get "/users" {}) [:headers "WWW-Authenticate"])))
    (is (= 401 (:status (call-h any :get "/users" {"authorization" "Basic abc"}))))
    (is (= 200 (:status (call-h any :get "/users" {"authorization" "Bearer whatever"}))))
    (is (= 403 (:status (call-h tok :get "/users" {"authorization" "Bearer nope"}))))
    (is (= 200 (:status (call-h tok :get "/users" {"authorization" "Bearer s3cret"}))))
    (is (= 200 (:status (call-h any :get "/_schemas" {}))) "meta routes need no token")
    (is (= 204 (:status (call-h any :options "/users" {}))) "preflight needs no token")))

(deftest simulated-latency
  (let [h (sim-handler {:delay [120 120]})
        t0 (System/nanoTime)]
    (call-h h :get "/users" {})
    (is (>= (/ (- (System/nanoTime) t0) 1e6) 100))
    (let [t1 (System/nanoTime)]
      (call-h h :get "/_schemas" {})
      (is (< (/ (- (System/nanoTime) t1) 1e6) 100) "meta routes are not delayed"))))

(deftest hot-reload
  (let [f (java.io.File/createTempFile "golem" ".edn")
        path (.getPath f)
        main-start (requiring-resolve 'golem.main/start!)]
    (try
      (spit f "{:todo [:map [:id :int] [:title :string]]}")
      (let [{:keys [server reload! stop-watch]} (main-start {:schemas path :port 0 :seed 1 :count 2})
            port (.getLocalPort (first (.getConnectors server)))
            get* #(slurp (str "http://localhost:" port %))]
        (try
          (is (= 2 (count (j/read-value (get* "/todos")))))
          (spit f "{:note [:map [:id :int] [:text :string]]}")
          (reload!)
          (is (= 2 (count (j/read-value (get* "/notes")))))
          (is (thrown? java.io.IOException (get* "/todos")))
          (spit f "{:note [:map [:id :int]")
          (reload!)
          (is (= 2 (count (j/read-value (get* "/notes")))) "a broken file keeps the old schemas")
          (finally (when stop-watch (stop-watch)) (.stop server))))
      (finally (.delete f)))))
