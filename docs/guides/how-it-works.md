---
seo_title: "How Migrax works"
description: "How Migrax turns JPA entities into SQL migrations: the entity model, the committed snapshot, the diff, rollback scripts, history, checksums and locking."
---
# How Migrax works

## The pieces

<figure class="mx-diagram" markdown="0">
<svg viewBox="0 0 690 390" role="img" aria-labelledby="mx-how-title">
<title id="mx-how-title">Entities are read into a schema model, compared with the snapshot, and the difference becomes a migration file with a rollback script; migrax migrate applies it to the database and records it in migrax_history.</title>
<defs><marker id="mx-how-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path class="mx-diagram__head" d="M0 0L10 5L0 10z"/></marker></defs>
<rect class="mx-diagram__box" x="10" y="20" width="110" height="48" rx="10"/>
<text x="65" y="49" text-anchor="middle">Entities</text>
<path class="mx-diagram__line" d="M120 44H266" marker-end="url(#mx-how-arrow)"/>
<text class="mx-diagram__label" x="194" y="30" text-anchor="middle">your JPA provider's mapping</text>
<text class="mx-diagram__label" x="194" y="62" text-anchor="middle">(or the annotations)</text>
<rect class="mx-diagram__box" x="270" y="20" width="140" height="48" rx="10"/>
<text x="340" y="49" text-anchor="middle">Schema model</text>
<rect class="mx-diagram__box" x="10" y="112" width="250" height="48" rx="10"/>
<text class="mx-diagram__mono" x="135" y="141" text-anchor="middle">.migrax/snapshot.json</text>
<path class="mx-diagram__line" d="M410 44H452Q470 44 470 62V70" marker-end="url(#mx-how-arrow)"/>
<path class="mx-diagram__line" d="M260 136H392Q408 136 408 120V106Q408 96 418 96H426" marker-end="url(#mx-how-arrow)"/>
<rect class="mx-diagram__accent" x="430" y="68" width="80" height="44" rx="10"/>
<text class="mx-diagram__on-accent" x="470" y="95" text-anchor="middle">Diff</text>
<path class="mx-diagram__line" d="M470 112V208" marker-end="url(#mx-how-arrow)"/>
<text class="mx-diagram__label" x="482" y="168">migrax generate</text>
<rect class="mx-diagram__box" x="330" y="212" width="280" height="58" rx="10"/>
<text class="mx-diagram__mono" x="470" y="237" text-anchor="middle">0002_add_customers_phone.sql</text>
<text class="mx-diagram__label" x="470" y="257" text-anchor="middle">+ rollback script</text>
<path class="mx-diagram__line" d="M470 270V316" marker-end="url(#mx-how-arrow)"/>
<text class="mx-diagram__label" x="482" y="298">migrax migrate</text>
<path class="mx-diagram__box" d="M410 328a60 10 0 0 0 120 0a60 10 0 0 0-120 0v38a60 10 0 0 0 120 0v-38"/>
<text x="470" y="360" text-anchor="middle">Database</text>
<path class="mx-diagram__line" d="M530 352H556" marker-end="url(#mx-how-arrow)"/>
<rect class="mx-diagram__box" x="560" y="330" width="122" height="44" rx="10"/>
<text class="mx-diagram__mono" x="621" y="357" text-anchor="middle">migrax_history</text>
</svg>
</figure>

**Schema model.** Migrax builds a model of the tables your entities need. When your project has
Hibernate 4 or newer, EclipseLink or OpenJPA, Migrax asks **your own JPA provider** for its
mapping, without connecting to a database, so names, types, join tables, element collections,
inheritance and sequences are exactly what your application expects. Without one of them it
scans the `jakarta.persistence` / `javax.persistence` annotations instead.

**Snapshot.** `.migrax/snapshot.json` records the schema your migrations produce. `generate`
compares the entities with the snapshot and writes only the difference. The snapshot also records
the dialect and [naming strategy](../reference/naming.md), so the names stay stable even if
your configuration changes later.

The very first `generate` has no snapshot, so it compares with the live database instead. When
that database already has tables, it also writes them to `0001_baseline.sql` and records that
migration as applied there without running it, so an empty database can still be built from
the migrations alone. The baseline has no rollback script: undoing it would drop every table.

**Migration files.** Migrations live in `src/main/resources/db/migration` and are numbered:
`0001_initial.sql`, `0002_add_customers_phone.sql`, ... The name describes the change. Next to
each generated migration, `rollback/<same name>.sql` undoes it.

**History.** `migrax migrate` records every applied migration in the `migrax_history` table,
with a SHA-256 checksum of the file. Failed attempts are recorded in `migrax_failures`.

## What `generate` does

1. Compiles the project if sources changed, and asks Maven or Gradle (preferring `./mvnw` /
   `./gradlew`) for the runtime classpath. The classpath is cached in `.migrax/classpath.txt`
   and refreshed when the build file changes.
2. Reads the entities into a schema model.
3. Compares it with the snapshot and works out the operations: create and drop tables, add,
   drop, rename and alter columns, keys, indexes, unique constraints, check constraints,
   comments and sequences.
4. Asks about likely renames, and refuses drops unless you pass `--allow-destructive`
   (see [Changing entities](changing-entities.md)).
5. Writes the migration and its rollback script, lints the SQL, and updates the snapshot.

`migrax plan` shows the same SQL without writing anything.

## What `migrate` does

1. Takes a database lock, so two instances starting at the same time don't both migrate.
2. Checks the history: an applied file that changed or disappeared stops the run.
3. Applies pending migrations in order, each in its own transaction with its history record.
4. Runs repeatable `R__*.sql` migrations that are new or changed, and the callbacks.

If a migration fails, its transaction is rolled back (where the database supports transactional
DDL) and the failure is recorded. `migrate` then refuses to continue until you have looked at
it; see [Rollbacks and recovery](rollbacks-and-recovery.md).

## Locking

| Database | Lock |
|---|---|
| PostgreSQL | `pg_advisory_lock` |
| YugabyteDB | `pg_advisory_lock` (2025.1 and newer), else a row in `migrax_lock` |
| CockroachDB | A row in `migrax_lock` (CockroachDB has no session locks) |
| MySQL, MariaDB, TiDB | `GET_LOCK` |
| SQL Server | `sp_getapplock` |
| Oracle | `DBMS_LOCK` (needs `EXECUTE` on `DBMS_LOCK`) |
| HSQLDB, Derby | A row in `migrax_lock` |
| H2 | Single-process database, no lock needed |
| SQLite | An operating-system lock on `<database>.migrax-lock` next to the database file |
