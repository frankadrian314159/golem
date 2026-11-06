# Troubleshooting

## Startup

**Nothing happens / usage text appears**
You did not pass `--schemas` or `--specs`. At least one is required.

**`Entity :x must be a :map schema`**
Each entity must be a `[:map …]` schema, optionally wrapped as `{:schema [:map …]}`. Scalars and vectors are not entities.

**`No s/keys specs registered in my.ns`**
`--specs` only converts `s/keys` specs whose key is in that namespace. Check the namespace name and that the specs are defined with `::name` or `:my.ns/name`.

**`Could not locate my/ns__init.class` or a file-not-found error**
The namespace is not on the classpath. Pass a file path instead, or add the directory with `-Sdeps '{:paths ["src" "/path/to/app/src"]}'`. See [Importing](importing.md).

**`Malli source did not yield a map of entities`**
For a `.clj` file the last form must be the map itself (not a `def`). For `my.ns/var` check the var exists and holds a map.

**The process hangs while recursing, or a stack overflow**
An entity refers to itself by bare keyword. Use `[:ref :entity]` and make the recursive branch optional or a vector. See [Defining schemas](schemas.md).

**`Address already in use`**
Another process uses the port. Choose a different one with `-p`.

**`warning: … treated as :any`**
A spec used a predicate golem cannot convert. See [What does not convert](importing.md#what-does-not-convert).

## Requests

**Every POST returns 400 `missing required key`**
You must send all required fields except the id and the `created_at`/`updated_at` fields, which golem fills in. Optional fields need `{:optional true}` in the schema.

**A field I sent is missing from the response**
Fields not in the schema are silently dropped, including for `:closed` schemas. Add the field to the schema.

**PUT returns 404 for a record I want to create**
PUT replaces an existing record only. Use POST to create.

**My PATCH was rejected**
The merged record is validated. Read the `errors` object: it names the field and the constraint.

**Filtering returns nothing**
Filters compare text exactly, and are case sensitive. Check the spelling, and use dots for nested fields (`address.city`).

**Records I created disappeared**
Data is held in memory and resets on restart. For stable fixtures, use `--seed` and `--count`.

**Browser blocks the call**
Responses carry permissive CORS headers. Confirm you are calling the right port, and the request is going to golem and not your frontend dev server.

## Data

**Different data on every start**
No `--seed` was given. Use the seed printed at startup.

**Same schema, different data after editing it**
Expected. The data is derived from the schema, so changing it changes the data.

**Fewer records than `--count`**
Records with duplicate ids are dropped. This only happens for very narrow id types, for example a string limited to one or two characters.

**Names and titles look like gibberish**
Strings are random by default. Use enums, regexes, or custom overrides. See [Generated data](data-generation.md).
