# Defining schemas

A schema file is an EDN map. Each key is an **entity** name and each value is that entity's schema. Schemas use [Malli](https://github.com/metosin/malli) syntax.

```clojure
{:address {:schema [:map
                    [:street :string]
                    [:city :string]
                    [:zip [:string {:min 5 :max 5}]]]
           :expose? false}

 :user [:map
        [:id :uuid]
        [:name [:string {:min 1 :max 40}]]
        [:email :string]
        [:age {:optional true} [:int {:min 18 :max 99}]]
        [:address :address]
        [:created_at :string]
        [:updated_at :string]]}
```

## Entity forms

An entity value is either a bare Malli `:map` schema, or a config map:

| Key | Meaning | Default |
|---|---|---|
| `:schema` | The Malli `:map` schema | required |
| `:path` | URL path for the collection | pluralised entity name |
| `:id-key` | Which field identifies a record | the field named `id` |
| `:expose?` | Set `false` to hide the entity from the API | `true` |

Every entity must be a `:map` schema. golem reports an error naming the entity if it is not.

## Ids

golem looks for a field whose name is `id` (for example `:id` or `:user/id`). Use `:id-key` if yours is called something else:

```clojure
{:product {:schema [:map [:sku :string] [:name :string]] :id-key :sku}}
```

How ids behave depends on the field's type:

| Id type | Seeded records | New records (POST) |
|---|---|---|
| `:int` | `1, 2, 3, …` | one more than the largest existing id |
| `:uuid` or anything else | generated | a random UUID string |

If a client sends an id in a POST body, it is kept. If it already exists the response is `409`.

## Entities without an id (embedded objects)

An entity with no id field is treated as an **embedded value object**. It gets no endpoints and no seeded records of its own. It only exists inside other entities that refer to it, like `:address` above. (Setting `:expose? false` does the same for an entity that does have an id.)

## Relationships

Refer to another entity by its keyword:

```clojure
[:address :address]            ; field "address" holds an :address object
[:addresses [:vector :address]] ; a list of them
```

The relationship is embedded: the nested object is stored inside the parent record. golem lists these links under `refs` in `GET /_schemas`.

For a structure that refers to itself, use `[:ref …]` and give it a way to end (an optional field or a vector, which can be empty):

```clojure
{:comment [:map
           [:id :int]
           [:text :string]
           [:replies {:optional true} [:vector [:ref :comment]]]]}
```

A bare reference to itself (`:comment` inside `:comment`) overflows the stack when golem builds the schema, so always use `[:ref …]` for recursion.

## Field names

Plain (`:email`) and namespaced (`:user/email`) keys both work. With namespaced keys the JSON field is `"user/email"`, which is awkward in a frontend, so plain keys are usually nicer. Field-name based behaviour (value overrides, filtering) ignores the namespace.

Avoid entity names that collide with Malli's built-in types, such as `:map`, `:string` or `:int`.

## Optional fields and constraints

Standard Malli properties apply to both generated data and incoming requests:

```clojure
[:nickname {:optional true} :string]       ; may be absent
[:name [:string {:min 1 :max 40}]]         ; length limits
[:age [:int {:min 18 :max 99}]]            ; numeric range
[:role [:enum "admin" "member"]]           ; fixed choices
[:tags [:vector {:max 4} :string]]         ; bounded list
[:nick [:maybe :string]]                   ; string or null
```

Regular expressions are not valid in EDN files. Use them in a `.clj` schema file or a namespace instead (see [Importing](importing.md)):

```clojure
[:email [:re #"[a-z]+@example\.com"]]
```

## Fields golem fills in for you

On `POST` and `PUT`, golem supplies these when the client leaves them out:

- the **id** field
- `created_at` / `createdAt` and `updated_at` / `updatedAt`, if the entity declares them

The timestamp is an ISO-8601 string (for `:string` and `:inst` fields) or epoch milliseconds (for numeric fields). Any other missing required field is rejected with a `400`; golem does not invent values for them.
