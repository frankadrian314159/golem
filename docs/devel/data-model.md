# Data model

## Defs (importer output)

A map of entity name to either a Malli form or a config map:

```clojure
{:user [:map [:id :uuid] [:name :string]]
 :address {:schema [:map [:street :string]] :expose? false :path "/addresses" :id-key :id}}
```

`golem.registry/normalize` turns each into `{:name :form :path :id-key :expose?}`. Paths default to `"/" + pluralize(name)`.

## Entity (registry output)

`registry/build` returns `{:registry reg :entities {name entity}}`. Each entity map holds:

| Key | Meaning |
|---|---|
| `:name` | entity keyword, e.g. `:user` |
| `:form` | the Malli form it was built from |
| `:schema` | compiled `m/schema` using the shared registry |
| `:path` | collection route, e.g. `"/users"` |
| `:entries` | `[{:key k :optional? bool :type malli-type :ref target} ...]`, one per map field (`:ref` only if the field carries `:golem/ref`) |
| `:id-key` | the field that identifies a record, or `nil` |
| `:id-type` | the Malli type of the id field (`:int`, `:uuid`, …) |
| `:expose?` | true only if requested **and** the entity has an id key |
| `:refs` | set of other entity names mentioned in the form |
| `:fks` | `{"field-name" target-entity}`: foreign-key fields, own and inherited from embedded entities, computed by `registry/add-fks` after every entity is described |

Rules enforced in `describe`: an entity must be a `:map` schema (otherwise an `ex-info` naming the entity). An entity without an id key is an embedded value object: `:expose?` becomes false, so it gets no routes and no seeded records.

### Foreign keys

`add-fks` runs once all entities are described. `own-fks` marks a field when it has `{:golem/ref :target}` (target must be exposed; `false` opts out) or is named `<entity>_id` / `<entity>Id` for an exposed entity whose `:id-type` equals the field's type. The `:fks` map of an entity is its own plus those of every entity in its `:refs`, transitively, so fields in embedded value objects count. `gen/link-foreign-keys` then replaces matching fields anywhere in a generated item (matching on the field name) with ids from the `:pool` that `app/seed-db!` builds as it seeds entities in `app/seed-order`.

### Registry and references

The registry is `(mr/composite-registry m/default-registry {entity form ...})`. That is what lets one schema say `[:address :address]`. Recursive structures use `[:ref :entity]`; a bare self-reference overflows the stack during schema construction.

Malli's `:ref` only resolves **qualified** keywords, so `registry/build` also registers every entity as `:golem.entity/<name>` and rewrites `[:ref :user]` to `[:ref :golem.entity/user]` in the stored forms (`rewrite-refs`). References are collected from the original form first, so `:refs` still reports plain names. The rewrite is visible in `/_schemas` output.

`collect-refs` finds references by walking the form itself (skipping property maps and `:enum`/`:=` arguments) rather than the compiled schema, because compiled refs are opaque. References are informational (`/_schemas`); the registry does the real resolution.

## Store layout

One atom:

```clojure
{:user {:order ["id-1" "id-2"]
        :items {"id-1" {...} "id-2" {...}}}
 :post {...}}
```

- Ids are stored and looked up as **strings** (`(str id)`), whatever their schema type, because path parameters and query values arrive as strings.
- Items are the decoded Clojure values (UUID objects, dates) as validated by the schema, not JSON forms. Encoding to JSON happens at the edge in `wrap-encode-output`.
- `:order` preserves creation order for listing; a Clojure map alone would not.
- `query` supports equality filters on dotted field-name paths (matching on `(name key)`, so namespaces are ignored), plus `limit` and `offset`.

## Generated data

`gen/generate-items` produces items for one entity:

1. `base = seed + (hash (name entity))` so each entity has an independent stream.
2. Item `i` uses seed `base + i` and `malli.generator/generate` with `{:seed :size}`.
3. String-valued fields whose name has a registered override (`default-overrides`) are rewritten by `apply-overrides` (a `postwalk` over the item). If the rewritten item no longer validates, the original is kept.
4. For `:int` ids the id is replaced by `i + 1`.
5. Duplicate ids are dropped.

Overrides are pure functions of the generated string (`email-from`, `instant-from` use `hash`), which keeps output repeatable for a seed.

## Hydration

`hydrate/hydrate` walks the entity's `:entries`, in order:

- id key: use the forced id (PUT), else keep a supplied one, else generate (`next-int-id` for ints, `random-uuid` string otherwise)
- `updated_at`/`updatedAt` absent: current time
- `created_at`/`createdAt` absent: the existing record's value (PUT) or current time

Time is an ISO-8601 string, or epoch milliseconds for numeric field types. Hydration works on the JSON-shaped body, before decoding.

## Spec conversion model

`golem.import/convert` maps `s/form` output to Malli forms:

- keywords: an entity key becomes its name (a reference); other registered specs are inlined via their form; unregistered ones warn and become `:any`
- symbols: looked up in the `preds` table
- lists: dispatched by head symbol in `convert-seq`
- `s/and` is post-processed: `seq`/`not-empty` upgrade `:string` to min length 1, a bare type is dropped when a refined version exists (`:string` next to `[:re …]`), and bound lambdas (`(<= % n)`) from `s/double-in` become `:min`/`:max` via `bound-marker` and `apply-bounds`
- anything unrecognised: `warn!` (which appends to the `*warnings*` atom) and `:any`
