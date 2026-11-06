# Generated data

When the server starts, golem generates records for every exposed entity directly from its schema (using Malli's generator, which is built on `test.check`). Whatever the schema allows, the data satisfies.

## Seeds: repeatable data

```sh
clojure -M:run -s src/schemas.edn --seed 42
```

The same schemas and the same seed always produce the same records. Change either and the data changes.

- Without `--seed`, golem picks a random seed and prints it at startup. Re-run with that number to reproduce the session.
- Each entity derives its own sequence from the seed, so adding an entity does not reshuffle the others' data.
- Seeds fix the **starting data**. Ids and timestamps created by later `POST` requests (random UUIDs, current time) are not seeded.

## How much data

| Flag | Effect |
|---|---|
| `--count 20` | 20 records per entity (default 5) |
| `--size 30` | larger generated values: longer strings, bigger collections (default 10) |

Fields with limits (`:min`, `:max`, `{:max 4}` on vectors) are always respected.

## Realistic values for common fields

Plain generated strings are random alphanumerics. A few field names get built-in treatment, based on the name regardless of namespace, and applied only when the field is a string:

| Field name | Generated value |
|---|---|
| `email` | a valid-looking address ending in `@example.com` |
| `created_at`, `updated_at`, `createdAt`, `updatedAt` | an ISO-8601 timestamp |

The result is derived from the randomly generated string, so it stays repeatable for a given seed. If an override would break a field's constraints (for instance a `:max` length), golem falls back to the unmodified generated record.

For anything else you can steer values with the schema itself:

```clojure
[:status [:enum "draft" "published" "archived"]]
[:zip [:string {:min 5 :max 5}]]
[:email [:re #"[a-z]{3,8}@example\.com"]]   ; regexes need a .clj source, see Importing
```

To add your own field-name overrides (names, cities and the like) see [Embedding golem](embedding.md).

## Ids in seeded data

Integer ids are numbered `1..count`. UUID and string ids are generated; duplicates are dropped, so you may get slightly fewer records than `--count` for a very small id type (such as a tightly bounded string).

## Optional fields

`{:optional true}` fields appear in some records and not in others, so your frontend sees both shapes.
