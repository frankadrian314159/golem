# r/Clojure announcement draft

**Title:** golem: a mock REST server generated from your Malli schemas or clojure.spec specs

---

I've been annoyed for a while by how much hand-written fixture JSON a frontend needs before a backend exists, so I wrote **golem**: point it at your Malli schemas (or clojure.spec specs) and it serves a stateful REST API with generated data.

Repo: https://github.com/frankadrian314159/golem (MIT)

## The simplest possible example

One entity in an EDN file:

```clojure
;; examples/todo/schemas.edn
{:todo [:map
        [:id :int]
        [:title [:string {:min 1 :max 60}]]
        [:done :boolean]
        [:priority [:enum "low" "medium" "high"]]
        [:created_at :string]]}
```

```sh
clojure -M:run -s examples/todo/schemas.edn -p 3000 --seed 42
# or: bb mock-server -s examples/todo/schemas.edn -p 3000 --seed 42

curl localhost:3000/todos
curl "localhost:3000/todos?done=false&priority=high"
curl -X POST localhost:3000/todos -H "Content-Type: application/json" \
  -d '{"title":"Write the announcement","done":false,"priority":"high"}'
# 201, and the server added "id": 6 and "created_at"
curl -X PATCH localhost:3000/todos/6 -H "Content-Type: application/json" -d '{"done":true}'
curl -X POST localhost:3000/todos -H "Content-Type: application/json" -d '{"title":"","priority":"urgent"}'
# 400 {"error":"validation failed","errors":{"done":["missing required key"],
#      "title":["should be at least 1 character"],
#      "priority":["should be either \"low\", \"medium\" or \"high\""]}}
```

That gives you `GET/POST /todos` and `GET/PUT/PATCH/DELETE /todos/:id`. There are bigger examples in the repo: a three-entity blog API in Malli and a shop API written in plain `clojure.spec`.

## What it does

- **Schemas in:** Malli from an `.edn` file, a `.clj` file, or an `ns/var`; or clojure.spec from a namespace or file. Entities refer to each other by keyword (`[:address :address]`), and recursive ones with `[:ref :comment]`.
- **Data out:** records are generated with `malli.generator` (so test.check underneath). `--seed 42` gives the same data every time, so a team can share one command line and see identical records. A few field names get nicer values (`email` becomes an `@example.com` address, `created_at` an ISO timestamp), and you can add your own overrides.
- **State:** an atom. POST, PUT, PATCH (deep merge) and DELETE work and persist until you restart. Every write is re-validated against the schema.
- **Validation:** bad input gets a 400 with Malli's humanized errors. Ids and `created_at`/`updated_at` are filled in for you; other missing required fields are rejected rather than invented.
- **Extras:** equality filters including nested paths (`?address.city=Leeds`), `limit`/`offset`, CORS headers, and `GET /_schemas` to see what it understood.

## How it's built

Reitit for routing, Ring + Jetty for serving, jsonista for JSON, babashka.cli for the arguments, Malli doing most of the work. The pipeline is import, register, generate, store, serve, and each step is one small namespace.

The spec support is a one-way converter to Malli forms, so everything after import only deals with Malli. The converter handles the common cases (`s/keys` with `-un` keys, `s/and`, `s/or`, `s/nilable`, `s/coll-of`, `s/map-of`, `s/int-in`, `s/double-in`, enum sets, regex predicates, a few others). Anything it can't translate becomes `:any` and prints a warning on startup rather than failing.

## Size

| | files | lines | code lines* |
|---|---|---|---|
| `src/` (Clojure) | 7 | 666 | 586 |
| `test/` | 2 | 161 | 141 |

\* excluding blank and comment-only lines. 11 tests, 52 assertions. They call the Ring handler directly and include a run of both bigger examples. There are also user docs and a short developer guide under `docs/`.

## Limitations (please read before trying it)

- **JVM only.** `bb mock-server -s schemas.edn -p 3000` works, but Babashka is only the launcher: Jetty and Reitit don't run under Babashka, so the task shells out to the Clojure CLI and the server runs on the JVM. A pure-bb version would need http-kit and a different router.
- **No referential integrity.** Relationships are embedded copies or plain foreign-key fields. Generated foreign keys are random unless you constrain them (the examples use `[:int {:min 1 :max 5}]`), and deleting a record doesn't touch anything that mentions it.
- **In memory only.** Nothing survives a restart. That's deliberate for fixtures, but it's a limit.
- **Partial spec coverage.** Regex ops (`s/cat`, `s/*`), `s/merge`, multi-specs and arbitrary predicate functions fall back to `:any`.
- **Filtering is basic.** Equality only. No sorting or ranges yet.
- **Rough edges I know about:** integer id allocation isn't atomic, so two simultaneous POSTs can occasionally clash with a 409; non-numeric `limit`/`offset` give a 500; unknown JSON fields are silently dropped. They're all listed in `docs/devel/design-notes.md`.
- **Custom value overrides** are only available when you start it from code (`golem.app/seed-db!`), not from the command line.
- Young project: Clojure 1.12, Malli 0.16.4, tested on one machine (Windows 11, JDK 25, Babashka 1.13.229).

## Why not X?

There are good tools in this space already:

- **[moclojer](https://github.com/moclojer/moclojer)** is the closest Clojure project. You write each endpoint and its canned response by hand in YAML or EDN (or import OpenAPI or Postman), and it replays them. It's a good fit when you want exact, hand-written responses. As far as I can tell from its README it doesn't generate data from schemas or keep state between requests, which is the gap golem aims at.
- **Prism** and **[open-api-mocker](https://github.com/jormaechea/open-api-mocker)** mock from an OpenAPI document, and **json-server** serves a JSON file you maintain. If your contract already lives in OpenAPI, use those.

golem is for when your source of truth is already Malli or spec in a Clojure codebase and you'd like the mock to follow it, with generated, stateful data, without writing a second description. I looked for an existing Clojure project that does this and didn't find one, but I may well have missed something, so please point me at it if you know of one.

## Feedback

I'd particularly like to hear:

- which spec forms you use that should convert and don't
- whether relationships (generating real foreign keys from seeded ids) matter enough to build next
- whether anyone would use this from Babashka alone, which would justify the http-kit rewrite

Issues and PRs welcome. Thanks for reading.
