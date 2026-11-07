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
| Referential integrity is seed-time only | seeded foreign keys point at real records (`:golem/ref` or the `<entity>_id` convention), but POST/PUT/PATCH accept any id and deleting a record leaves dangling references; self-references and cycles are not linked | optional strict mode: check foreign keys in `store`, restrict or cascade deletes |
| Filters are string equality only | no ranges, partial match, sorting or full-text search | extend `store/query` and document the syntax |
| Unknown JSON fields are stripped silently | by default, even for `:closed` schemas; `--strict` rejects them | honour `:closed` per schema |
| Merge-patch `null` is all-or-nothing | a PATCH removes every null key, falling back to storing every null if that is invalid; a patch mixing an optional field and a required nullable one fails | decide per key from the schema |
| Persistence rewrites the whole file on every change | fine for fixtures, slow for large stores; a loaded snapshot is not re-validated against the schemas | debounce the writes, validate on load |
| Overrides unavailable from the CLI | only reachable via the embedding API | load a `.clj` overrides file |
| Self-referential structures need `[:ref …]` | a bare self-reference overflows the stack at schema construction | detect and report in `registry/build` |
| `rewrite-refs` matches any 2-element `[:ref x]` vector | a map field literally named `:ref` whose schema is an entity would be rewritten wrongly | skip `:map` entry positions |
| Spec coverage is partial | regex ops (`s/cat`, `s/*`), `s/merge`, multi-specs and arbitrary predicates become `:any` | extend `convert-seq` |
| Entity names can clash with Malli built-ins | `:map`, `:string` and similar shadow the real types in the registry | namespace entity keys inside the registry |

## Ideas not yet built

- `GET` sorting (`?sort=-created_at`) and range filters
- fault injection (`?_delay=500`, `?_status=503`)
