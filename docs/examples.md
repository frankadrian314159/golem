# Examples

Three examples live in [examples/](../examples): a one-entity todo server, and two complete multi-endpoint servers (blog and shop) that serve several related entities side by side. Each has a README with a request walkthrough.

| Example | Defined with | Entities and endpoints | Folder |
|---|---|---|---|
| Todo | Malli (`.edn`) | `/todos` (one entity, the smallest useful server) | [examples/todo](../examples/todo/README.md) |
| Blog | Malli (`.edn`) | `/authors`, `/posts`, `/comments` | [examples/blog](../examples/blog/README.md) |
| Shop | `clojure.spec` | `/customers`, `/products`, `/orders` | [examples/shop](../examples/shop/README.md) |

Run either from the project root:

```sh
bb blog        # or: clojure -M:run -s examples/blog/schemas.edn -p 3000 --seed 42
bb shop        # or: clojure -M:run --specs examples/shop/shop_specs.clj -p 3000 --seed 42
```

Then open `http://localhost:3000/posts` (blog) or `http://localhost:3000/orders` (shop). Both use `--seed 42`, so you see the same records every time.

## Blog (Malli)

[`schemas.edn`](../examples/blog/schemas.edn) defines three entities.

```
/authors    id, name, email, bio (optional), created_at
/posts      id, author_id, title, body, status, tags, stats{views, likes}, created_at, updated_at
/comments   id, post_id, author_name, text, created_at
```

Things to try:

- `GET /posts?status=published`, `GET /comments?post_id=2` and `GET /posts?author_id=3` for filtering on enum and foreign-key fields
- `GET /posts?limit=2&offset=2` for paging
- `POST /posts` without an id or timestamps, then `PATCH /posts/6` with `{"stats":{"views":10}}` to see a nested field change while its sibling is kept
- `POST /posts` with `{"title":"","status":"live"}` for the validation errors

## Shop (clojure.spec)

[`shop_specs.clj`](../examples/shop/shop_specs.clj) is an ordinary spec namespace. golem finds every `s/keys` spec in it.

```
/customers  id, name, email, address{street, city}, vip (optional), created_at
/products   id, sku (AAA-0000), title, price (1.0-500.0), category, in_stock
/orders     id, customer_id, items[{product_id, quantity}], status, created_at
```

`::address` and `::line-item` have no `id`, so they appear only inside customers and orders (see [Defining schemas](schemas.md#entities-without-an-id-embedded-objects)). The startup output has no `warning:` lines, because every spec form used is supported (see [Importing](importing.md)).

Things to try:

- `GET /products?category=books`
- `POST /orders` with `{"customer_id":2,"status":"pending","items":[{"product_id":3,"quantity":2}]}`, then `PATCH /orders/6` with `{"status":"paid"}`
- `POST /orders` with `{"customer_id":9,"items":[],"status":"lost"}` to see three field errors at once
- `GET /_schemas` to see how the specs were translated to Malli

## Foreign keys

Both examples rely on the `<entity>_id` naming convention (see [Foreign keys](schemas.md#foreign-keys)): seeded `author_id`, `post_id`, `customer_id` and `product_id` values point at records that exist, for any `--count`. Writes are not checked, so a POST can still reference a missing record, and deleting a record leaves references behind. See the [API notes](api.md#things-to-know).

## Using an example as a starting point

Copy the folder, edit the schema file, and start it with the same command. Anything under [Defining schemas](schemas.md) works in either format.
