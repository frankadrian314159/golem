# Embedding golem

You can start golem from a REPL or a test instead of the command line. This also unlocks custom value overrides, which the command line does not expose.

## Starting from a REPL

```clojure
(require '[golem.main :as main])

(def system (main/start! {:schemas "src/schemas.edn" :port 3000 :seed 42}))
;; => {:server <Jetty> :db <atom> :seed 42 :registry {...} :port 3000}

(.stop (:server system))
```

`start!` accepts `:schemas`, `:specs`, `:port`, `:seed`, `:count` and `:size`, the same as the command-line flags.

## Assembling the pieces yourself

```clojure
(require '[golem.import :as imp]
         '[golem.registry :as registry]
         '[golem.store :as store]
         '[golem.app :as app]
         '[ring.adapter.jetty :as jetty])

(let [defs (imp/load-defs {:schemas "src/schemas.edn"})
      reg  (registry/build defs)
      db   (store/create-db)]
  (app/seed-db! db reg {:seed 7 :count 10 :size 10})
  (def handler (app/make-handler reg db)))

(jetty/run-jetty handler {:port 3000 :join? false})
```

`handler` is an ordinary Ring handler. You can call it directly in tests without starting a server:

```clojure
(handler {:request-method :get :uri "/users" :headers {}})
;; => {:status 200 :headers {...} :body "[{...}]"}
```

## Custom value overrides

Overrides map a **field name** to a function that turns the generated string into the value you want. Pass your own map under `:overrides` when seeding, and merge with the defaults to keep the built-in email and timestamp behaviour:

```clojure
(require '[golem.gen :as gen])

(def overrides
  (merge gen/default-overrides
         {"city"  (fn [s] (nth ["Leeds" "York" "Hull" "Bath"] (mod (hash s) 4)))
          "phone" (fn [s] (str "+44 7" (format "%09d" (mod (hash s) 1000000000))))}))

(app/seed-db! db reg {:seed 7 :count 10 :overrides overrides})
```

Rules:

- The function receives the generated string and must return the replacement. Derive it from the input (as above) so the same seed gives the same data.
- It only applies to string values.
- The replacement must still satisfy the schema; otherwise golem keeps the original generated record.

## Working with the data directly

The store is a plain atom you can inspect or reset between tests:

```clojure
@db                        ; {:user {:order ["id1" ...] :items {"id1" {...}}} ...}
(reset! db {})             ; clear everything
(app/seed-db! db reg {:seed 7 :count 3})
```
