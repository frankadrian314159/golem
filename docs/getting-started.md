# Getting started

## Requirements

- A JVM (Java 11 or newer)
- The [Clojure CLI](https://clojure.org/guides/install_clojure)
- Optionally [Babashka](https://babashka.org), which is only a convenient launcher. The server itself runs on the JVM.

## Start the server

From the project directory:

```sh
bb mock-server -s src/schemas.edn -p 3000 --seed 42
```

or, without Babashka:

```sh
clojure -M:run -s src/schemas.edn -p 3000 --seed 42
```

The first run downloads dependencies, which can take a minute. When it is ready you will see something like:

```
Mock server on http://localhost:3000  (seed 42, reuse with --seed 42)
  /users  [GET POST]   /users/:id  [GET PUT PATCH DELETE]
  /posts  [GET POST]   /posts/:id  [GET PUT PATCH DELETE]
  /_schemas  [GET]
```

If you do not pass `--seed`, golem picks a random one and prints it. Pass that number later to get the same data again.

## Command-line options

| Flag | Meaning | Default |
|---|---|---|
| `-s`, `--schemas` | Malli source: an `.edn` file, a `.clj` file, or a namespace/var | |
| `--specs` | `clojure.spec` source: a namespace or a `.clj` file | |
| `-p`, `--port` | Port to listen on | `3000` |
| `--seed` | Number that fixes the generated data | random |
| `--count` | Records generated per entity | `5` |
| `--size` | Generator size. Larger values give longer strings and bigger collections | `10` |
| `-h`, `--help` | Show usage | |

You must supply `--schemas`, `--specs`, or both. See [Importing](importing.md).

## Your first requests

```sh
# list generated users
curl localhost:3000/users

# fetch one (copy an id from the list)
curl localhost:3000/users/78182bb0-e676-4faf-9282-44f8a72338e9

# create one; id, created_at and updated_at are filled in for you
curl -X POST localhost:3000/users \
  -H "Content-Type: application/json" \
  -d '{"name":"Ada","email":"ada@example.com","address":{"street":"1 Main","city":"Leeds","zip":"12345"}}'

# change a nested field
curl -X PATCH localhost:3000/users/<id> -H "Content-Type: application/json" \
  -d '{"address":{"city":"York"}}'

# delete
curl -X DELETE localhost:3000/users/<id>

# see what golem knows about your schemas
curl localhost:3000/_schemas
```

Try sending something invalid, such as `{"name":""}`, to see the validation errors.

## Using it from a frontend

The server answers browser preflight requests and sends `Access-Control-Allow-Origin: *`, so a dev server on another port can call it directly. Point your app's API base URL at `http://localhost:3000`.

Because the same seed gives the same data, share the command line (including `--seed`) with your team and everyone sees identical records.

## Stopping

Press `Ctrl+C`. Everything created or changed while running is discarded.
