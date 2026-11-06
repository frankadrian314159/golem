# Extending golem

Each recipe lists what to change, and what must be updated alongside it.

## Support another clojure.spec form

Inspect what spec actually produces first. Many macros expand to lambdas, and the form you see in `s/form` is often not the one you wrote:

```clojure
(require '[clojure.spec.alpha :as s])
(s/form (s/double-in :min 0.0 :max 5.0))
;; (clojure.spec.alpha/and clojure.core/double? (clojure.core/fn [%] (clojure.core/<= % 5.0)) ...)
```

Then in `src/golem/import.clj`:

1. **A plain predicate** (`clojure.core/foo?`): add an entry to the `preds` map.
2. **A spec macro** (`s/foo`): add a case to `convert-seq`, keyed by the fully qualified head symbol, returning a Malli form. Convert child forms with `(convert ents child)` so entity references and warnings keep working.
3. **A lambda pattern inside `s/and`**: recognise it in `bound-marker` (for numeric bounds) or alongside the `clojure.core/fn` case in `convert-seq` (for stand-alone patterns).
4. Anything you cannot represent should call `warn!` and return `:any`. Do not throw; unsupported forms degrade to warnings by design.

Also:

- Add a case to `test/golem/example_specs.clj` and an assertion in `core_test.clj` (`import-clojure-spec` / `spec-refinements`).
- Add a row to the conversion table in `docs/importing.md`.

## Add a Malli source type

`golem.import/malli-defs` dispatches on the source string (extension, then `/`, then namespace). Add a branch that returns a map, and keep the final `normalize-defs` call so Schema objects and qualified keys are handled.

## Add a field-name override

For users, overrides are passed to `app/seed-db!` (see [Embedding](../embedding.md)). To ship a new built-in, add it to `default-overrides` in `src/golem/gen.clj`:

```clojure
"phone" (fn [s] (str "+44 7" (format "%09d" (mod (hash s) 1000000000))))
```

Keep overrides **pure functions of the input string** so seeds stay repeatable. Update `docs/data-generation.md`.

To expose overrides on the command line you would need a way to express functions (a `.clj` file, loaded like `malli-defs`), and then thread them from `start!` into `seed-db!` opts, where they already flow to `gen/generate-item`.

## Add a hydrated field

`hydrate/hydrate` decides by field name. Add a `cond` branch that returns `(assoc body key value)` when the key is absent. Because hydration runs before validation, the value must be in JSON form (strings, numbers), not a decoded type.

## Add a route or middleware

- **New per-entity route:** add it to the vector in `entity-routes` in `src/golem/app.clj`. Entity-wide middleware goes in the `{:middleware ...}` map next to the path; per-method middleware goes in that method's map.
- **New global middleware:** add it to the threading form at the bottom of `make-handler`. Decide its position carefully, see the order described in [Architecture](architecture.md#request-pipeline). Anything that produces its own error response should sit inside `wrap-json` and `wrap-cors`.
- **New error type:** throw `(ex-info msg {:type :your-type ...})` from the store or handler and add a `case` branch in `wrap-exceptions`.
- **New endpoint outside the entities** (like `/_schemas`): add it to `meta-routes`. Prefix with an underscore to avoid colliding with entity paths.

## Add a store operation

Write it in `src/golem/store.clj` as a single `swap!` so it stays atomic. Call `check!` on any item before it is stored, so every mutation is re-validated against the schema.

## Add a CLI option

1. Add the flag to `cli-spec` in `src/golem/main.clj` (an alias and a `:coerce` if it is not a string).
2. Read it in `start!`.
3. Mention it in `usage`, `docs/getting-started.md` and the root `README.md`.

## Libraries to know

| Library | Used for |
|---|---|
| `metosin/malli` | schemas, validation, generation, transformation, error messages |
| `test.check`, `test.chuck` | generators; `test.chuck` is required for `[:re …]` strings |
| `reitit-ring` | routing |
| `ring-core`, `ring-jetty-adapter` | middleware and server |
| `jsonista` | JSON |
| `babashka.cli` | argument parsing |
