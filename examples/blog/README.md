# Blog example (Malli)

Three related entities defined in [schemas.edn](schemas.edn): **authors**, **posts** and **comments**.

```sh
# from the project root
bb blog
# or
clojure -M:run -s examples/blog/schemas.edn -p 3000 --seed 42
```

| Endpoint | Entity |
|---|---|
| `/authors`, `/authors/:id` | `:author` (optional `bio`) |
| `/posts`, `/posts/:id` | `:post` (status enum, tag list, nested `stats`) |
| `/comments`, `/comments/:id` | `:comment` |

Relationships are plain integer fields (`post.author_id`, `comment.post_id`). golem recognises them as [foreign keys](../../docs/schemas.md#foreign-keys) from their names, so seeded posts and comments always point at records that exist, whatever `--count` is.

## Try it

```sh
# everything about one post
curl localhost:3000/posts/1

# filtering: published posts, a post's comments, one author's posts
curl "localhost:3000/posts?status=published"
curl "localhost:3000/comments?post_id=2"
curl "localhost:3000/posts?author_id=3"

# paging
curl "localhost:3000/posts?limit=2&offset=2"

# create a post: id (6), created_at and updated_at are filled in
curl -X POST localhost:3000/posts -H "Content-Type: application/json" \
  -d '{"author_id":1,"title":"Hello","body":"First!","status":"draft","tags":["intro"],"stats":{"views":0,"likes":0}}'

# publish it and bump a nested field (the rest of "stats" is kept)
curl -X PATCH localhost:3000/posts/6 -H "Content-Type: application/json" \
  -d '{"status":"published","stats":{"views":10}}'

# validation: bad enum value, empty title, missing fields
curl -X POST localhost:3000/posts -H "Content-Type: application/json" \
  -d '{"title":"","status":"live"}'

# remove it
curl -X DELETE localhost:3000/posts/6
```

## What it demonstrates

- several entities served side by side from one file
- enums, bounded vectors, optional fields and nested maps
- filtering by a foreign key, paging, deep-merge `PATCH`
- hydration of an integer id and timestamps on `POST`
- readable `400` errors
