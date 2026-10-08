# Hibernate versions

With Hibernate 5.4 or newer in your project, Migrax asks **your Hibernate version itself** for
the mapping, without connecting to a database. The generated schema is therefore exactly what
your application expects: names, types, join tables, element collections, inheritance and
sequences.

## Compatibility

Every version below is tested on each change to Migrax. A fixture project with identity and
sequence ids, enums, embedded types, element collections (sets, ordered lists, embeddables),
one-to-many, many-to-one and many-to-many relationships, single-table inheritance and
`@Version` is generated, migrated and verified. **That same Hibernate version then validates the
schema**, and the rollback scripts are tested.

| Hibernate | Entities | Typical frameworks | Status |
|---|---|---|---|
| 7.0 – 7.4 | `jakarta.persistence` | Spring Boot 4, recent Quarkus 3 releases, Micronaut 5 | <span class="mx-ok">Supported</span> |
| 6.3 – 6.6 | `jakarta.persistence` | Spring Boot 3.2 to 3.5, Quarkus 3, Micronaut 4, Helidon 4 | <span class="mx-ok">Supported</span> |
| 6.0 – 6.2 | `jakarta.persistence` | Spring Boot 3.0 and 3.1, early Quarkus 3 releases | <span class="mx-ok">Supported</span> |
| 5.4 – 5.6 | `javax.persistence` | Spring Boot 2.2 to 2.7 | <span class="mx-ok">Supported</span> |
| before 5.4 | `javax.persistence` | Spring Boot 2.1 and older | Annotation scanning only |

Tested patch versions: 5.4.33, 5.5.9, 5.6.15, 6.0.2, 6.1.7, 6.2.52, 6.3.2, 6.4.10, 6.5.3,
6.6.58, 7.0.10, 7.1.36, 7.2.25, 7.3.13 and 7.4.12.

## Without Hibernate, or before 5.4

Migrax then scans the JPA annotations and applies Hibernate 6's rules. That covers the common
mappings, but types and sequence names can differ from older Hibernate versions (Hibernate 5
uses one shared `hibernate_sequence`, for example). `migrax doctor` warns when this applies. Check
generated migrations carefully and run `migrax verify`.

## Choosing the reader

| `--extractor` | Behavior |
|---|---|
| `auto` (default) | Hibernate's mapping when possible, otherwise annotation scanning |
| `hibernate` | Hibernate's mapping only; fails with the reason if it can't be used |
| `annotations` | Annotation scanning only |

`migrax doctor` shows which reader is used. `migrax inspect` prints the schema it read.
