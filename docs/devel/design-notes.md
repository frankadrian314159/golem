# Design notes and limitations

## Decisions

**JVM server, Babashka launcher.** Jetty and Reitit do not run under Babashka, so `bb.edn` only shells out to the Clojure CLI. A pure Babashka server would mean replacing Jetty with http-kit and Reitit with a smaller router.

**Malli is the internal language.** Everything is normalised to Malli forms. `clojure.spec` support is a one-way converter in `golem.import`, so the rest of the system has one model to reason about.

**Unsupported spec forms degrade, not fail.** An unknown predicate becomes `:any` with a startup warning. Failing would make importing a real-world spec namespace all-or-nothing.

**Embedding, not linking.** Relationships are copies inside records (`[:address :address]`). Keeping it document-shaped avoids a join layer and matches what a frontend consumes. The cost is no referential integrity (see below).

**Entities without an id are embedded objects.** This lets one convention cover addresses, line items and the like, for both Malli and spec sources, without an extra flag.

**Overrides are pure functions of the generated value.** That keeps seeds meaningful: no second source of randomness.

**Hydrate only server-owned fields.** Ids and timestamps are filled in; other missing required fields get a `400`. Silently inventing values would hide client bugs, which defeats a mock server's purpose of exercising the real contract.

**Store validates every write.** Validation is in the store as well as the middleware, so PATCH (which merges server-side) and any future caller cannot store an invalid record.

**Strings for ids.** Ids are normalised to strings in the store so URL parameters, query values and typed ids all compare the same way.

## Known limitations

| Limitation | Effect | Possible fix |
|---|---|---|
| No referential integrity | foreign keys are plain values; deleting a record leaves dangling references; generated foreign keys are random unless constrained | add a `:ref` convention in the importer and have `gen` sample real ids from already-seeded entities |
| Integer id allocation is not atomic | `next-int-id` reads the store, then `insert!` writes; two simultaneous POSTs can compute the same id and the second gets `409` | allocate the id inside the `swap!` in `insert!` |
| `limit` / `offset` are parsed with `parse-long` | non-numeric values cause a `500` | validate and return `400` |
| Filters are string equality only | no ranges, partial match, sorting or full-text search | extend `store/query` and document the syntax |
| Unknown JSON fields are stripped silently | even for `:closed` schemas | optionally reject, based on schema properties |
| State is in memory | nothing survives a restart | snapshot the atom to EDN/JSON on exit and reload |
| Overrides unavailable from the CLI | only reachable via the embedding API | load a `.clj` overrides file |
| Self-referential structures need `[:ref …]` | a bare self-reference overflows the stack at schema construction | detect and report in `registry/build` |
| `rewrite-refs` matches any 2-element `[:ref x]` vector | a map field literally named `:ref` whose schema is an entity would be rewritten wrongly | skip `:map` entry positions |
| Spec coverage is partial | regex ops (`s/cat`, `s/*`), `s/merge`, multi-specs and arbitrary predicates become `:any` | extend `convert-seq` |
| Entity names can clash with Malli built-ins | `:map`, `:string` and similar shadow the real types in the registry | namespace entity keys inside the registry |
| No authentication, latency or error injection | the mock is always fast and always allowed | middleware options |

## Ideas not yet built

- `GET` sorting (`?sort=-created_at`) and range filters
- fault injection (`?_delay=500`, `?_status=503`)
- OpenAPI output generated from the registry
- resetting state over HTTP (`POST /_reset`)
- persistence between runs
