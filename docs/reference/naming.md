# Naming strategies

Hibernate turns entity and field names into table and column names, and each framework
configures that differently. Migrax must use exactly the same rules, or the generated tables
won't be the ones your application looks for.

| Strategy | Used by | `homepageURL` | `fullName` | Join table for `Product.tags` |
|---|---|---|---|---|
| `spring` | Spring Boot | `homepageurl` | `full_name` | `product_tags` |
| `jpa` | Quarkus, Helidon, Jakarta EE, plain Hibernate, Micronaut without Micronaut Data | `homepageURL` | `fullName` | `Product_Tag` |
| `micronaut` | Micronaut Data | `homepage_url` | `full_name` | `product_tag` |
| `jpa-snake` | Hibernate with only a snake_case physical strategy | `homepageurl` | `full_name` | `product_tag` |

Names you give explicitly with `@Table(name = ...)` or `@Column(name = ...)` pass through the
same physical rule: Spring Boot and Micronaut Data turn them into snake_case too.

## How Migrax chooses

1. An explicit setting: `--naming`, `MIGRAX_NAMING` or `migrax.naming`.
2. The naming recorded in `.migrax/snapshot.json` by the first `generate`. This keeps names
   stable even if the configuration changes later, for example when the database URL moves into
   environment variables.
3. A naming strategy configured for Hibernate, such as
   `spring.jpa.hibernate.naming.physical-strategy` or `hibernate.physical_naming_strategy`.
4. The framework's default, detected from the build file and configuration.

`migrax init` and `migrax doctor` show the result. When the snapshot's naming differs from what
your framework uses, `doctor` warns and explains how to switch.

!!! warning "Changing the naming of an existing project"

    Switching naming renames every affected column. Set `migrax.naming`, run `migrax generate`,
    and accept the renames it proposes, so the data is kept.

## Sequences

Generated ids (`@GeneratedValue` with `AUTO` or `SEQUENCE` and no named generator) use:

| Hibernate | Sequence |
|---|---|
| 6 and 7 | `<entity>_SEQ` per entity, allocation size 50 |
| 6 and 7 with Micronaut Data | one `hibernate_sequence` |
| 5.x | one `hibernate_sequence`, allocation size 1 |

On MySQL, which has no sequences, Hibernate uses a one-row `next_val` table of the same name,
and so does Migrax.
