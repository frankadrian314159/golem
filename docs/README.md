# golem user guide

golem turns your data schemas into a running mock REST API. You describe your entities once (with Malli schemas or `clojure.spec` specs) and golem serves them with realistic, repeatable fake data, validates what clients send, and remembers changes in memory until you stop it (or, with `--persist`, in a file between runs).

## Contents

| Guide | Read it when you want to… |
|---|---|
| [Getting started](getting-started.md) | install, start the server and make your first requests |
| [Defining schemas](schemas.md) | describe entities, relationships, ids and custom paths |
| [Importing Malli and spec](importing.md) | reuse schemas you already have in your application |
| [API reference](api.md) | see every endpoint, status code and error shape |
| [Generated data](data-generation.md) | control seeds, counts and what fake values look like |
| [Examples](examples.md) | run the blog (Malli) and shop (spec) multi-endpoint servers |
| [Embedding golem](embedding.md) | start it from a REPL or tests, or add custom value overrides |
| [Troubleshooting](troubleshooting.md) | fix a startup error, a surprising 400 or odd data |

Working on golem itself? See the [developer guide](devel/README.md).

## How it works in one minute

1. golem reads your schemas and registers each entity (a `:map` schema with an `id`) in one registry.
2. For every entity it generates a few records from the schema, using a seed so the same seed always gives the same data.
3. It serves `GET/POST /things` and `GET/PUT/PATCH/DELETE /things/:id` for each entity.
4. Incoming JSON is checked against the schema. Bad input gets a `400` with readable messages.
5. All data lives in memory. Restarting the server resets it to the freshly generated data, unless you start with `--persist file.edn`. `POST /_reset` regenerates it on demand, and `GET /_openapi` describes the API for other tools.
