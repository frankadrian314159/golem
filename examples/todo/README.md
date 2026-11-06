# Todo example (Malli)

One entity, [schemas.edn](schemas.edn), is enough for a complete REST API:

```sh
clojure -M:run -s examples/todo/schemas.edn -p 3000 --seed 42
```

```sh
curl localhost:3000/todos
curl "localhost:3000/todos?done=false&priority=high"
curl -X POST localhost:3000/todos -H "Content-Type: application/json" \
  -d '{"title":"Write the announcement","done":false,"priority":"high"}'
curl -X PATCH localhost:3000/todos/6 -H "Content-Type: application/json" -d '{"done":true}'
curl -X POST localhost:3000/todos -H "Content-Type: application/json" -d '{"title":"","priority":"urgent"}'
curl -X DELETE localhost:3000/todos/6
```

The id (`6`) and `created_at` are filled in by the server. The last `POST` is rejected with a `400` that names each bad field.
