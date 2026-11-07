# golem

A schema-driven mock REST server for Clojure. Give it [Malli](https://github.com/metosin/malli) schemas or `clojure.spec` specs and it boots a stateful, validating API with deterministic fake data.

Full user documentation is in [docs/](docs/README.md), runnable multi-endpoint [examples](docs/examples.md) are in [examples/](examples), and contributor notes are in the [developer guide](docs/devel/README.md).

## Quick start

```sh
bb mock-server -s src/schemas.edn -p 3000 --seed 42
# without Babashka:
clojure -M:run -s src/schemas.edn -p 3000 --seed 42
```

Requires the Clojure CLI and a JVM. Babashka is optional and only launches the JVM app (`bb.edn`).

## Options

| Flag | Meaning | Default |
|---|---|---|
| `-s`, `--schemas` | Malli source (see below) | |
| `--specs` | `clojure.spec` source (see below) | |
| `-p`, `--port` | HTTP port | `3000` |
| `--seed` | Seed for generated data; same seed gives same data | random (printed at startup) |
| `--count` | Records generated per entity | `5` |
| `--size` | Generator size (larger gives bigger values) | `10` |
| `--persist` | EDN file to keep the data in: loaded at startup if it exists, rewritten after every change | off |
| `--strict` | Reject unknown JSON fields with a `400` instead of silently dropping them | off |
| `--watch` | Reload the schema/spec file when it changes (data is re-seeded; with `--persist` existing data is kept and only new entities are seeded; a broken file keeps the old schemas) | off |
| `--delay` | Simulated latency in ms: `300`, or a random range `200-800` | off |
| `--error-rate` | Fraction (0 to 1) of requests that fail with a random 500/502/503/504; repeatable with `--seed` | off |
| `--auth [token]` | Require `Authorization: Bearer <token>`: `401` if missing, and with a token given `403` if it doesn't match | off |

At least one of `--schemas` or `--specs` is required; both can be given and are merged.

## Defining entities

Each top-level key is an entity whose value is a Malli `:map` schema, or a config map `{:schema … :path "/custom" :id-key :id :expose? false}`.

```clojure
{:address {:schema [:map [:street :string] [:city :string]] :expose? false}
 :user    [:map
           [:id :uuid]
           [:name [:string {:min 1 :max 40}]]
           [:email :string]
           [:address :address]
           [:created_at :string]]}
```

- Entities refer to each other by keyword (`:address`); use `[:ref :entity]` for recursive structures.
- Field names may be plain (`:email`) or namespaced (`:user/email`).
- An entity with no `id` field is an embedded value object: no routes, no seeded records.
- Paths default to the pluralised entity name (`:user` gives `/users`).

### Importing

| Source | `-s` / `--schemas` (Malli) | `--specs` (`clojure.spec`) |
|---|---|---|
| File | `.edn`, or `.clj(c)` whose last form is the entity map | `.clj(c)` starting with an `(ns …)` form |
| Namespace | `my.ns` (needs a `schemas` var) or `my.ns/var` | `my.ns` |

For specs, every registered `s/keys` spec in the namespace becomes an entity, and one `s/keys` spec nested in another becomes a reference. Supported forms: common predicates, `s/and`, `s/or`, `s/nilable`, `s/coll-of`, `s/map-of`, `s/int-in`, `s/double-in`, enum sets, regex predicates, and `seq`/`not-empty` on strings. Anything else is approximated as `:any` and reported as a `warning:` at startup.

## Endpoints

For an exposed entity at `/users`:

| Method | Path | Behaviour |
|---|---|---|
| GET | `/users` | List; filter with `?field=value` (dotted paths like `address.city`), page with `limit` and `offset` |
| POST | `/users` | Validate, hydrate, store; 201 with `Location` |
| GET | `/users/:id` | Fetch one |
| PUT | `/users/:id` | Replace (must be a complete, valid record) |
| PATCH | `/users/:id` | Deep-merge a partial update, then re-validate |
| DELETE | `/users/:id` | Remove; 204 |
| GET | `/_schemas` | Introspect entities, paths, references and schema forms |
| GET | `/_openapi` | OpenAPI 3.1 description of the whole API, generated from the schemas |
| POST | `/_reset` | Discard all changes and regenerate the seed data (`?seed=N` to use a different seed) |

Invalid input returns `400` with Malli's human-readable errors, for example `{"error":"validation failed","errors":{"email":["missing required key"]}}`. Unknown records return `404`, duplicate ids `409`. CORS headers are sent on every response.

**Hydration:** on POST and PUT the server fills in the id (UUID, or the next integer for `:int` ids) and any `created_at`/`updated_at` (`createdAt`/`updatedAt`) fields. PUT keeps the original `created_at`. Other missing required fields are rejected, not invented.

## Generated data

Data comes from `malli.generator` (test.check) and is fully determined by `--seed`. Field-name overrides keep values realistic: `email` becomes a valid-looking address and the timestamp fields become ISO-8601 instants (see `default-overrides` in `src/golem/gen.clj`). Integer ids are sequential. Regex schemas (`[:re …]`) generate through `test.chuck`.

**Foreign keys:** a field marked `{:golem/ref :user}`, or named `<entity>_id` / `<entity>Id` for an entity with the same id type, is seeded with ids of records that exist (entities are seeded in dependency order). See [Defining schemas](docs/schemas.md#foreign-keys).

State lives in a single in-memory atom and resets on restart unless you pass `--persist file.edn`; every mutation is re-validated against the entity's schema.

## Development

```sh
clojure -M:test      # run the tests
```

| Namespace | Role |
|---|---|
| `golem.openapi` | OpenAPI document generated from the registry |
| `golem.import` | Load Malli or spec sources into entity definitions |
| `golem.registry` | Central Malli registry; detects ids and entity references |
| `golem.gen` | Seeded generation and field overrides |
| `golem.store` | In-memory CRUD layer with validation on write |
| `golem.hydrate` | Server-owned fields (ids, timestamps) |
| `golem.app` | Reitit routes and Ring middleware |
| `golem.main` | CLI and Jetty |

## License

[MIT](LICENSE.md)
