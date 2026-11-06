# Importing Malli and spec

golem can read schemas from plain files or from schemas you already define in code. You can use either source, or both together (the results are merged).

## Malli (`-s` / `--schemas`)

| You pass | golem does |
|---|---|
| `src/schemas.edn` | reads the EDN map |
| `src/schemas.clj` or `.cljc` | loads the file; its **last form** must evaluate to the entity map |
| `my.app.schemas/schemas` | requires the namespace and dereferences that var |
| `my.app.schemas` | requires the namespace and uses its var named `schemas` |

The value must be a map of entity name to schema, exactly as described in [Defining schemas](schemas.md).

Because code sources are evaluated, they can use things EDN cannot, such as regexes and Malli `Schema` objects:

```clojure
;; src/my_schemas.clj
(ns my-schemas
  (:require [malli.core :as m]))

(def schemas
  {:user (m/schema [:map
                    [:id :uuid]
                    [:email [:re #"[a-z]+@example\.com"]]])})
```

```sh
clojure -M:run -s my-schemas/schemas
```

Qualified entity keys such as `:app/user` are accepted and renamed to `:user`, and references to them inside other schemas are renamed too.

Namespaces must be on the classpath. When you run golem from its own checkout that means `src/` (and `test/` only if you add it). To load code from elsewhere, add the directory with `-Sdeps`:

```sh
clojure -Sdeps '{:paths ["src" "/path/to/your/app/src"]}' -M -m golem.main -s your.ns/schemas
```

## clojure.spec (`--specs`)

| You pass | golem does |
|---|---|
| `my.app.specs` | requires the namespace |
| `src/my_specs.clj` | loads the file; it must start with an `(ns …)` form |

Every `s/keys` spec registered **in that namespace** becomes an entity. Its name is the spec name without the namespace (`::user` becomes `:user`).

```clojure
(ns my.app.specs
  (:require [clojure.spec.alpha :as s]))

(s/def ::street string?)
(s/def ::city string?)
(s/def ::address (s/keys :req-un [::street ::city]))

(s/def ::id uuid?)
(s/def ::name (s/and string? seq))
(s/def ::email (s/and string? #(re-matches #"[a-z]+@example\.com" %)))
(s/def ::age (s/int-in 18 100))
(s/def ::role #{:admin :member})
(s/def ::user (s/keys :req-un [::id ::name ::email ::address ::role]
                      :opt-un [::age]))
```

```sh
clojure -M:run --specs src/my_specs.clj
```

This produces a `:user` entity with routes and an embedded `:address` (it has no `id`). Prefer `:req-un` / `:opt-un` so JSON fields are `"email"` rather than `"my.app.specs/email"`.

### What converts

| Spec | Becomes |
|---|---|
| `string?` `int?` `integer?` `boolean?` `keyword?` `symbol?` `uuid?` `inst?` `any?` `nil?` | the matching Malli type |
| `pos-int?` `nat-int?` `neg-int?` | `:int` with min/max |
| `double?` `float?` `number?` | `:double` (no NaN or infinity, so it stays valid JSON) |
| `(s/and …)` | `:and`, with these tidy-ups below |
| `(s/or :a x :b y)` | `:orn` |
| `(s/nilable x)` | `:maybe` |
| `(s/coll-of x)`, `s/every` | `:vector` (or `:set` with `:kind set?`), honouring `:min-count`, `:max-count`, `:count` |
| `(s/map-of k v)` | `:map-of` |
| `(s/int-in a b)` | `:int` with min `a`, max `b - 1` |
| `(s/double-in :min m :max n)` | `:double` with min/max (spec expands this into lambda bounds; golem reads them back as `:min`/`:max`) |
| `#{:a :b}` | `:enum` |
| `#(re-matches #"…" %)` | `:re` (a string regex) |
| `seq` / `not-empty` inside `s/and` with `string?` | `:string` with min length 1 |
| a nested `s/keys` spec that is an entity | a reference to that entity |
| a nested `s/keys` spec that is not in the namespace | an inline `:map` |

### What does not convert

Anything not in the table, for example arbitrary predicate functions like `#(> (count %) 3)`, `s/merge`, or sequence regex ops such as `s/cat`, is approximated as `:any` and golem prints a warning at startup:

```
warning: unsupported predicate (clojure.core/fn [%] ...), treated as :any
```

The server still starts, but those fields accept and generate any value. If a field matters, switch it to Malli or use a `.clj` Malli source for that entity.
