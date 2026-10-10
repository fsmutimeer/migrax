# Switching from Flyway or Liquibase

A project that already manages its database with Flyway or Liquibase can move to Migrax with
one command, without running anything again on the database. `migrax import` reads the other
tool's history and records it in Migrax's own history (`migrax_history`).

## From Flyway

Flyway's files already live where Migrax looks for migrations
(`src/main/resources/db/migration`), and their names work unchanged: `V1__init.sql`,
`V2__add_email.sql` and repeatable `R__views.sql` files are ordered by their version.

```console
$ migrax import flyway
Recorded 12 migration(s) as applied.
Next: disable Flyway (spring.flyway.enabled=false), then run 'migrax generate'; its first run uses the current database as the baseline.
```

What it does:

- Every migration that Flyway applied successfully (`success` is true in
  `flyway_schema_history`) is recorded as applied, so `migrax migrate` doesn't run it again.
- Java migrations Flyway ran are recorded by their class name.
- A Flyway baseline (`baselineVersion`) is respected: files up to that version are recorded as
  applied too.
- Failed Flyway migrations are not recorded; `migrax migrate` runs them like new files.
- A file Flyway applied that is no longer in the folder is skipped, with a note.

If the history table has another name, pass it with `--table` (for example
`--table my_schema_history`).

## From Liquibase

Liquibase's changelogs are XML, YAML or SQL that Migrax doesn't run. Instead, the import writes
the database's current schema as one SQL migration, a baseline, and records it as applied:

```console
$ migrax import liquibase
Wrote 0001_liquibase_baseline.sql with 9 table(s) from the current schema.
Recorded 1 migration(s) as applied.
Next: disable Liquibase (spring.liquibase.enabled=false), then run 'migrax generate'; its first run uses the current database as the baseline.
```

The baseline has the tables, keys, indexes and the sequences your entities use, so a new
environment or `migrax verify` builds the whole schema from it. The database the import ran on
already has all of it and never runs the file. Review its column types before you commit it;
`--name` chooses another file name.

## Afterwards

1. Remove the other tool, or switch it off (`spring.flyway.enabled=false`,
   `spring.liquibase.enabled=false`, or your framework's setting), so that only Migrax migrates
   at startup.
2. Run `migrax generate`. With no snapshot yet, it compares the entities with the database and
   writes only what is really different; usually it finds nothing and saves the snapshot.
3. Run `migrax verify` to check that the migrations build the schema your entities expect.

The other tool's history tables (`flyway_schema_history`, `DATABASECHANGELOG`,
`DATABASECHANGELOGLOCK`) stay in the database; Migrax ignores them. Drop them once you no
longer need them.

The import is safe to run again: migrations already in Migrax's history are not recorded twice.
