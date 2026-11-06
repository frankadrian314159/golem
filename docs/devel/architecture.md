# Architecture

golem is a pipeline: **import → register → generate → store → serve**.

```
schema file / ns ──► golem.import ──► defs {entity form}
                                         │
                                         ▼
                                  golem.registry/build ──► {:registry reg :entities {...}}
                                         │
                       ┌─────────────────┴──────────────────┐
                       ▼                                    ▼
                golem.gen (seeded data)              golem.app (routes)
                       │                                    │
                       ▼                                    ▼
                golem.store  ◄──────────────  handlers / middleware
                                                            ▲
                                       golem.hydrate  ──────┘
                                                            ▲
                                golem.main (CLI + Jetty) ───┘
```

## Namespaces

| Namespace | File | Responsibility |
|---|---|---|
| `golem.import` | `src/golem/import.clj` | Turn Malli or clojure.spec sources into a defs map `{entity-name schema-or-config}`. The spec converter lives here. |
| `golem.registry` | `src/golem/registry.clj` | Build the shared Malli registry and describe each entity (path, id key, entries, references). |
| `golem.gen` | `src/golem/gen.clj` | Seeded generation with `malli.generator`, plus field-name overrides. |
| `golem.store` | `src/golem/store.clj` | In-memory CRUD over one atom; validates every write. |
| `golem.hydrate` | `src/golem/hydrate.clj` | Fills server-owned fields (ids, timestamps) before validation. |
| `golem.app` | `src/golem/app.clj` | Reitit routes, Ring middleware, JSON handling, error mapping. |
| `golem.main` | `src/golem/main.clj` | CLI parsing (`babashka.cli`), startup, Jetty. |

Dependencies point downward: `main` uses `app`, `import`, `registry`, `store`; `app` uses `gen`, `hydrate`, `store`; nothing depends on `main`.

## Startup sequence

`golem.main/start!`:

1. `golem.import/load-defs` reads `:schemas` (Malli) and/or `:specs` (spec) and merges them. Spec conversion warnings travel as `{:warnings [...]}` metadata on the returned map; `start!` prints them.
2. `golem.registry/build` creates a composite registry (`m/default-registry` plus one entry per entity form), then `describe`s each entity.
3. `golem.store/create-db` makes the atom.
4. `golem.app/seed-db!` generates `count` records for each exposed entity and inserts them.
5. `golem.app/make-handler` builds the Ring handler and `ring.adapter.jetty/run-jetty` serves it. It returns `{:server :db :seed :registry :port}` so tests and REPL users can stop the server and inspect state.

`-main` only parses arguments and prints the endpoint list; everything else is in `start!` so it is testable.

## Request pipeline

`make-handler` wraps the Reitit ring handler as:

```
wrap-cors            outermost: answers OPTIONS, adds CORS headers to every response
  wrap-params        parses query string into :query-params
    wrap-json        reads body into :body-params (400 on bad JSON); serialises response bodies
      wrap-exceptions  maps ex-info :type to 400/404/409, anything else to 500
        reitit router
```

Order matters: `wrap-exceptions` sits inside `wrap-json` so error maps are JSON-encoded, and inside `wrap-cors` so error responses still carry CORS headers. The default Reitit handler supplies JSON 404 and 405 bodies.

Each exposed entity contributes routes from `entity-routes`:

```
/users              (all methods)   wrap-encode-output
  ""      GET       list            store/query
          POST      create          wrap-validate-input → store/insert!
  /:id    GET       fetch           store/fetch
          PUT       replace         wrap-validate-input → store/replace!
          PATCH     merge           decode → store/patch!
          DELETE    remove          store/delete!
```

- **`wrap-validate-input`** (POST and PUT): checks the body is an object; for PUT loads the existing record (404 if missing); calls `hydrate/hydrate`; decodes with the JSON transformer (extra keys stripped); validates; responds `400` with `me/humanize` output, or passes `:entity-body` on.
- **`wrap-encode-output`**: for 2xx responses encodes the stored Clojure values back to JSON forms (UUID, dates) with `m/encode`, for both single records and lists.
- **PATCH** has no input middleware. The handler decodes the partial body and `store/patch!` validates the merged result, so an invalid patch raises `ex-info {:type :invalid}`, which `wrap-exceptions` turns into a `400`.

There is also `GET /_schemas` (`meta-routes`), which reports each entity's path, references and schema form.

## Error flow

The store throws `ex-info` with `:type` in `#{:invalid :not-found :conflict}`; `wrap-exceptions` is the only place those become HTTP statuses. Middleware that can answer directly (bad body, PUT on a missing record) returns the response without throwing.

## Concurrency

The store is one atom and every write is a single `swap!`, so individual writes are atomic. Jetty serves requests on multiple threads. See [Design notes](design-notes.md) for the one known race (integer id allocation).
