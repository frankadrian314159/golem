# r/Clojure announcement draft

**Title:** golem: a mock REST server generated from your Malli schemas or clojure.spec specs

---
A frontend needs large amounts of hand-written fixture JSON before a backend exists, so I wrote **golem**: point it at your Malli schemas (or clojure.spec specs) and it serves a stateful REST API with generated data.

N.B. Yes, I used Claude Sonnet 5.5 to write this. No, I'm not ashamed. It works and the entire project was cranked out in less than three hours of wall-clock time. Anyone who doesn't use this kind of force multiplier for tooling is foolish.

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
- **State:** an atom. POST, PUT, PATCH (deep merge) and DELETE work and persist until you restart (or forever with `--persist file.edn`). `POST /_reset` regenerates the seed data. Every write is re-validated against the schema.
- **Validation:** bad input gets a 400 with Malli's humanized errors. Ids and `created_at`/`updated_at` are filled in for you; other missing required fields are rejected rather than invented.
- **Testing aids:** `--delay 200-800` for latency, `--error-rate 0.1` for random 5xx, `--auth` for a fake Bearer check, `X-Golem-Status: 503` (or `?_status=503`) to fail one request, `--strict` to reject unknown fields, and `--watch` to reload the schema file when it changes.
- **Extras:** equality filters including nested paths (`?address.city=Leeds`), `limit`/`offset`, CORS headers, `GET /_schemas` to see what it understood, and `GET /_openapi` for an OpenAPI 3.1 description generated from your schemas. Fields marked `{:golem/ref :user}` (or named `user_id`) are seeded with ids of records that exist.

## How it's built

Reitit for routing, Ring + Jetty for serving, jsonista for JSON, babashka.cli for the arguments, Malli doing most of the work. The pipeline is import, register, generate, store, serve, and each step is one small namespace.

The spec support is a one-way converter to Malli forms, so everything after import only deals with Malli. The converter handles the common cases (`s/keys` with `-un` keys, `s/and`, `s/or`, `s/nilable`, `s/coll-of`, `s/map-of`, `s/int-in`, `s/double-in`, enum sets, regex predicates, a few others). Anything it can't translate becomes `:any` and prints a warning on startup rather than failing.

## Size

| folder | files | lines | code lines* |
|---|---|---|---|
| `src/` (Clojure) | 8 | 946 | 840 |
| `test/` | 2 | 288 | 254 |

\* excluding blank and comment-only lines. 19 tests, 101 assertions. They call the Ring handler directly and include a run of both bigger examples. There are also user docs and a short developer guide under `docs/`.

## Limitations (please read before trying it)

- **JVM only.** `bb mock-server -s schemas.edn -p 3000` works, but Babashka is only the launcher: Jetty and Reitit don't run under Babashka, so the task shells out to the Clojure CLI and the server runs on the JVM. A pure-bb version would need http-kit and a different router.
- **Referential integrity is seed-time only.** Seeded foreign keys point at real records, but writes aren't checked and deleting a record doesn't touch anything that mentions it.
- **In memory by default.** `--persist file.edn` keeps state between runs by rewriting the whole file after each change, which is fine for fixtures but not for big data.
- **Partial spec coverage.** Regex ops (`s/cat`, `s/*`), `s/merge`, multi-specs and arbitrary predicate functions fall back to `:any`.
- **Filtering is basic.** Equality only. No sorting or ranges yet.
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

