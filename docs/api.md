# API reference

For every **exposed** entity (one with an id, unless `:expose? false`), golem serves a collection route and an item route. The examples use `/users`.

All request and response bodies are JSON. Send `Content-Type: application/json`.

## Endpoints

| Method | Path | Success | Purpose |
|---|---|---|---|
| GET | `/users` | 200, array | list, filter, page |
| POST | `/users` | 201, object | create |
| GET | `/users/:id` | 200, object | fetch one |
| PUT | `/users/:id` | 200, object | replace |
| PATCH | `/users/:id` | 200, object | partial update |
| DELETE | `/users/:id` | 204, no body | remove |
| GET | `/_schemas` | 200, array | describe entities |
| OPTIONS | any | 204 | CORS preflight |

The collection path defaults to the pluralised entity name (`:category` gives `/categories`, `:box` gives `/boxes`) and can be set per entity with `:path`.

## Listing, filtering and paging

```
GET /users
GET /users?role=admin
GET /users?address.city=Leeds&role=admin
GET /posts?published=true&limit=10&offset=20
```

- Any query parameter other than `limit` and `offset` is an **equality filter** on the field with that name. Matching compares text, so `age=30`, `published=true` and ids all work as written.
- Use dots to reach nested fields: `address.city`.
- Field names match regardless of namespace (`email` matches `:user/email`).
- Several filters combine with AND.
- `limit` and `offset` take whole numbers. Records come back in creation order.
- A filter that matches nothing returns `[]`.

## Creating (POST)

```sh
curl -X POST localhost:3000/users -H "Content-Type: application/json" \
  -d '{"name":"Ada","email":"ada@example.com","address":{"street":"1 Main","city":"Leeds","zip":"12345"}}'
```

Steps golem performs:

1. Fill in server-owned fields: the id (unless you supplied one) and any `created_at`/`updated_at`.
2. Convert JSON values to schema types (strings to UUIDs, dates and so on). Unknown fields are dropped.
3. Validate against the entity schema.
4. Store it and respond `201` with the full record and a `Location: /users/<id>` header.

## Replacing (PUT)

The body must be a complete, valid record. The id always comes from the URL, whatever the body says. `updated_at` is refreshed if you omit it, and `created_at` is kept from the stored record if you omit it. `404` if the record does not exist.

## Partial update (PATCH)

Send only the fields to change. Objects are **merged deeply**, so `{"address":{"city":"York"}}` changes the city and leaves the rest of the address alone. Arrays and scalar values are replaced. The id cannot be changed. The merged result is validated; if it is invalid nothing changes and you get a `400`. `404` if the record does not exist.

## Deleting

`204` with no body, or `404` if the record does not exist.

## Describing the API: `/_schemas`

```sh
curl localhost:3000/_schemas
```

Returns one object per entity with its `entity` name, `path`, whether it is `exposed`, the entities it references (`refs`), and the Malli `schema` as data. Useful for generating frontend types.

## Value formats

| Schema type | JSON |
|---|---|
| `:uuid` | string |
| `:keyword` | string |
| `:inst` | ISO-8601 string |
| `:int`, `:double` | number |
| `:vector`, `:set` | array |
| `:enum` of keywords | string |

## Errors

Errors are JSON objects with an `error` message.

| Status | When | Body |
|---|---|---|
| 400 | body is not valid JSON | `{"error":"invalid JSON body"}` |
| 400 | body is not a JSON object | `{"error":"body must be a JSON object"}` |
| 400 | body does not match the schema | `{"error":"validation failed","errors":{…}}` |
| 404 | unknown path or record | `{"error":"not found"}` |
| 405 | method not supported on that path | `{"error":"method not allowed"}` |
| 409 | POST with an id that already exists | `{"error":"already exists"}` |
| 500 | unexpected problem | `{"error":"…"}` |

Validation errors are keyed by field, with human-readable messages, and nest for nested objects:

```json
{
  "error": "validation failed",
  "errors": {
    "email": ["missing required key"],
    "age": ["should be at least 18"],
    "address": {"zip": ["should be at most 5 characters"]}
  }
}
```

## Things to know

- Data is **in memory only**. A restart returns to the originally generated records.
- Relationships are embedded copies, not live links. Changing a user does not update posts that mention it, and deleting a record does not cascade.
- There is no authentication.
