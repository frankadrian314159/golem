# golem developer guide

For people changing golem itself. If you only want to use it, see the [user guide](../README.md).

| Guide | Read it when you want to… |
|---|---|
| [Architecture](architecture.md) | understand the namespaces, startup sequence and request pipeline |
| [Data model](data-model.md) | know the shape of an entity map, the store and the generated data |
| [Extending golem](extending.md) | support a new spec form, add a route, middleware or override |
| [Testing](testing.md) | run the tests, add new ones, or experiment with scratch scripts |
| [Design notes and limitations](design-notes.md) | learn why things are the way they are, and what is known to be missing |

## Setup

```sh
git clone <repo> golem && cd golem
clojure -M:test      # fetches dependencies, runs the suite
clojure -M:run -s src/schemas.edn --seed 42
```

Requirements: a JVM and the Clojure CLI. Babashka is optional (`bb.edn` only shells out to `clojure`).

## Project layout

```
deps.edn            dependencies and the :run / :test aliases
bb.edn              Babashka tasks (mock-server, blog, shop, test)
src/schemas.edn     default sample schemas
src/golem/          the implementation (see Architecture)
test/golem/         tests and fixtures
examples/           blog (Malli) and shop (clojure.spec) servers
docs/               user guide; docs/devel is this guide
```

## Conventions

- Plain Clojure data and functions; the only mutable state is the store atom.
- Private helpers are `defn-`. Namespace docstrings say what the namespace is for.
- Anything user-visible (flags, endpoints, supported spec forms, error shapes) needs a matching update in the user guide in the same change.
