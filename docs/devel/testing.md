# Testing

## Running

```sh
clojure -M:test        # or: bb test
```

The `:test` alias adds `test/` to the classpath, runs `golem.core-test`, and exits non-zero on failure. The first run downloads dependencies.

## What is covered

All tests live in `test/golem/core_test.clj`:

| Test | Checks |
|---|---|
| `registry-test` | references and id-key detection |
| `seeding-is-deterministic` | same seed gives identical items; email override shape |
| `crud-flow` | list, get, nested PATCH, invalid PATCH, delete, 404 |
| `create-validates-and-hydrates` | 400 on bad input, hydrated id and timestamps, PUT keeps `created_at`, integer id increment |
| `filtering` | dotted-path query filters |
| `import-malli-from-var` | `ns/var` source |
| `import-clojure-spec` | spec conversion, references, regex and enum generation |
| `spec-refinements` | `s/int-in`, `seq` on strings, no warnings |
| `blog-example`, `shop-example` | both examples boot, serve every collection, validate and mutate |
| `recursive-entities` | `[:ref :entity]` builds, generates, and accepts nested POST bodies |
| `foreign-keys-detected` | explicit `:golem/ref`, `false` opt-out, naming convention, type mismatch, keys inherited from embedded entities |
| `seeded-foreign-keys-point-at-real-records`, `foreign-key-seeding-is-deterministic` | seeded keys exist in the target entity across several seeds; same seed gives the same links |
| `nested-foreign-keys-and-dependency-order` | the shop example: targets seeded first, keys inside line items linked |
| `mutually-dependent-entities-still-seed` | a foreign-key cycle does not break seeding |
| `reset-endpoint` | `POST /_reset` restores, reseeds with `?seed=`, rejects bad seeds, absent without seed options |
| `persistence-round-trip` | autosave writes every change; `load!` restores uuids and all |
| `openapi-document` | paths, schema `$ref`s resolve, legal component names, patch has no required fields, recursion, `/_reset` listed |

`test/golem/example_specs.clj` holds the spec fixtures and a `schemas` var used by the Malli-var test.

## How tests call the server

The tests do not start Jetty. They call the Ring handler directly:

```clojure
(defn- call [h method uri & [body]]
  (let [resp (h {:request-method method :uri uri :headers {}
                 :body (when body (java.io.ByteArrayInputStream. (.getBytes (j/write-value-as-string body))))})]
    (assoc resp :json (some-> (:body resp) (j/read-value (j/object-mapper {:decode-key-fn keyword}))))))
```

`call` returns the response with the parsed body under `:json`. Build a handler with fresh state for each test:

```clojure
(let [db (store/create-db)]
  (app/seed-db! db reg {:seed 7 :count 3 :size 10})
  (app/make-handler reg db))
```

Use a fixed seed so failures are reproducible.

## Adding tests

- Put new cases in `core_test.clj` (or a new namespace, and add it to the `:test` alias's `main-opts` in `deps.edn`).
- Fixed seeds only. Assert properties of generated data (ranges, patterns) rather than exact values unless you are testing determinism itself.
- For a new example or schema file, mirror `blog-example` or `shop-example`: build the registry, seed, then hit every collection.
- For new importer behaviour, add the spec to `example_specs.clj` and assert on both the converted result and on generated data (a conversion that looks right can still be ungeneratable).

## Scratch scripts and real HTTP

To try something against a real server, write a script and run it from the project root (paths such as `examples/blog/schemas.edn` are relative):

```clojure
;; scratch.clj
(require '[golem.main :as main])
(let [{:keys [server]} (main/start! {:schemas "examples/blog/schemas.edn" :port 3201 :seed 42})]
  (try
    (println (slurp "http://localhost:3201/posts"))
    (finally (.stop server))))
```

```sh
clojure -M scratch.clj
```

Always stop the server in a `finally`, otherwise the JVM keeps running. To use test fixtures from a script add them to the classpath: `clojure -Sdeps '{:paths ["src" "test"]}' -M scratch.clj`.

## Pitfalls we have already hit

- Malli transformers such as `mt/json-transformer` and `mt/strip-extra-keys-transformer` are **functions that return a transformer**: call them, `(mt/json-transformer)`. Passing the function itself fails with `::m/invalid-transformer` at request time.
- `m/encode` and `m/decode` take `schema value transformer`. Do not use them with `some->>`, which appends the value last.
- `[:and :string [:re …]]` cannot be generated reliably; the generator filters random strings and gives up. Use `[:re …]` alone (this needs `test.chuck`). The importer already drops the redundant `:string`.
- EDN files cannot contain regex literals; use a `.clj` source.
- `double?` specs must exclude NaN and infinity, which JSON cannot represent; the importer adds `:gen/NaN? false :gen/infinite? false`.
- A change to a schema or to generation changes seeded data, so fixed-value expectations in tests will break; prefer property assertions.
