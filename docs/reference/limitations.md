---
seo_title: "Limitations"
description: "What Migrax doesn't do yet, and what to do instead."
---
# Limitations

What Migrax doesn't do yet, and what to do instead.

## Reading your entities

**Hibernate older than 4.0.** Migrax reads these projects from their annotations, using the
rules of current Hibernate versions. Most mappings come out the same, but older versions differ
in places: they share one `hibernate_sequence` between entities, for example.
`migrax doctor` tells you when this applies. Review the generated migrations and run
`migrax verify` before you release them. See [Entity readers](hibernate.md).

**JPA providers other than Hibernate, EclipseLink and OpenJPA**, such as DataNucleus, are read
the same way, with Hibernate's naming rules. If your provider names tables or columns differently, set
the names in your entities (`@Table`, `@Column`) or check the generated migrations.

## Reading a schema file

**Sequences, tables in another schema, and MySQL's `ON UPDATE CURRENT_TIMESTAMP`** in a
[schema file](../guides/sql-migrations-for-any-language.md#let-migrax-write-the-migrations)
are not read; Migrax warns when the file has them. Identity columns work. Write migrations for
sequences and other schemas yourself (`migrax new`), and add `ON UPDATE` to the generated
migration.

**Views, functions, procedures and triggers** belong in
[repeatable migrations](../guides/advanced-migrations.md#repeatable-migrations), not in the
schema file.

## Projects without Java

**Migrax itself runs on Java 17 or newer.** Projects in other languages don't need Java on
their machines: use the [container image](../guides/containers-and-kubernetes.md). They still
get generated migrations: describe the schema in a
[schema file](../guides/sql-migrations-for-any-language.md#let-migrax-write-the-migrations),
or export it from your ORM ([Schema file from your ORM](../guides/schema-from-orm.md)), and
`migrax generate` writes the migrations and their rollback scripts from it.

**Migrating at startup** works in Java applications (Spring Boot, Quarkus, Micronaut, Helidon,
Jakarta EE). Elsewhere, run `migrax migrate` before the application starts, for example as a
step of the deployment or an init container.

## Moving data

**Migrax changes the structure, not the values.** It adds, renames, changes and drops columns
and tables, and keeps the values of renamed ones, but it can't know how values should change
shape, such as splitting `full_name` into `first_name` and `last_name`. Write that step
yourself, in Java or SQL, between two generated migrations:
[Moving data between columns](../guides/advanced-migrations.md#moving-data-between-columns).

## Changing columns

**Oracle: a smaller NUMBER column in a key, index, unique constraint or check.** Oracle makes
a `NUMBER` column smaller (fewer decimals or fewer digits before the point) only while the
column is empty. For other columns Migrax moves the values through a new column, which rewrites
the table; a trigger keeps the new column up to date while the application goes on writing, and
writes fail for a moment only while the old column is dropped. Dropping a column in a key,
index, unique constraint or check would drop those too, so there the migration stops with
ORA-01440 once the table has rows, and changes nothing. The same goes for identity columns. An
index, constraint or column grant that only the database has (made by hand), or a function-based
index on the table, stops the move with a message before anything is changed. Keep the larger
type, or write the change yourself (`migrax new`): drop the key, index or constraint, add a
column with the new type, copy the values into it, drop the old column, give the new one its
name, and create the key, index or constraint again.

## Rolling back

**A rollback brings back the structure, not the data**, unless the migration was generated with
`--keep-data`. Rolling back a migration that dropped a column re-creates the column, but its
values are gone; with `--keep-data` they come back from a backup table. See
[Keeping dropped data](../guides/rollbacks-and-recovery.md#keeping-dropped-data).
