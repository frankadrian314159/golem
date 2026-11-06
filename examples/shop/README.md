# Shop example (clojure.spec)

A shop API defined with plain `clojure.spec` in [shop_specs.clj](shop_specs.clj). golem converts the specs to Malli on startup.

```sh
# from the project root
bb shop
# or
clojure -M:run --specs examples/shop/shop_specs.clj -p 3000 --seed 42
```

| Endpoint | Entity |
|---|---|
| `/customers`, `/customers/:id` | `::customer` (embeds an address, optional `vip`) |
| `/products`, `/products/:id` | `::product` (SKU pattern, price range, category) |
| `/orders`, `/orders/:id` | `::order` (embeds a list of line items) |

`::address` and `::line-item` have no `id`, so they are embedded objects and get no endpoints. `customer_id` and `product_id` are limited to `1..5`, so with the default `--count 5` they point at records that exist.

## Try it

```sh
curl localhost:3000/products
curl "localhost:3000/products?category=books"
curl localhost:3000/orders/1

# place an order: id and created_at are filled in
curl -X POST localhost:3000/orders -H "Content-Type: application/json" \
  -d '{"customer_id":2,"status":"pending","items":[{"product_id":3,"quantity":2}]}'

# mark it paid
curl -X PATCH localhost:3000/orders/6 -H "Content-Type: application/json" \
  -d '{"status":"paid"}'

# rejected: unknown status, empty item list, customer out of range
curl -X POST localhost:3000/orders -H "Content-Type: application/json" \
  -d '{"customer_id":9,"items":[],"status":"lost"}'

# see how the specs were translated
curl localhost:3000/_schemas
```

## What it demonstrates

- importing existing `clojure.spec` specs with `--specs`
- spec forms that convert cleanly: `s/keys` with `:req-un`/`:opt-un`, `s/and`, `s/int-in`, `s/double-in`, `s/coll-of` with count limits, enum sets, a regex predicate, `seq` on strings
- embedded value objects (`::address`, `::line-item`)
- the startup prints no conversion warnings, because every form used here is supported
