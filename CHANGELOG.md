# Changelog

All notable changes to Migrax are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org).

## [Unreleased]

### Changed

- `generate` and `diff` now drop columns after adding and changing the table's other columns.
  MySQL, MariaDB, Oracle and H2 commit each schema change at once, so when a later statement
  of a migration failed, for example making a new column NOT NULL while it still has empty
  rows, the old column and its data were already gone. Now the migration stops while they are
  still there. The order of the summary lines changes the same way, and so can the name of
  the generated file.

### Fixed

- `generate` gave its file the number of a Java migration written with `migrax new --java`
  when that number was the highest one, because it counted only SQL files. Both migrations
  then ran in an order nobody chose (the SQL file first). Java migrations now count, and
  `generate`, `check` and `doctor` report a SQL file and a Java migration that share a number,
  with how to fix it: rename the one that is not applied yet. A project that already has such
  a pair fails `check` (exit code 1) until then, unless both are applied in its database.

- Quarkus: the `migrax.migrations.applied` and `migrax.migrations.failed` counters and the
  `migrax.run` timer of the migrations at startup could stay at 0 in the application's
  registries (Prometheus, OTLP, ...), depending on the order in which Quarkus ran its startup
  steps. They now always count the startup run.

## [0.17.1] - 2026-10-25

### Added

- Every GitHub release lists the libraries it contains in `migrax-<version>-sbom.cdx.json`
  (CycloneDX), and each of its files has a signed record of the workflow run that built it:
  `gh attestation verify <file> -R fsmutimeer/migrax` checks it. The container image carries
  its own software list and build record in the registry. See Download > Check your
  download.

### Fixed

- Renaming a table no longer needs `--allow-destructive`. Hibernate's foreign key names
  contain the table name, so `generate --rename-table` (or answering "yes" to "Did you
  rename table ...?") dropped the old key and added it again under its new name, and the drop
  was counted as a destructive change. A foreign key that comes back unchanged under a new
  name in the same migration is no longer destructive; one that is really removed or changed
  still is. It is listed as "rename foreign key ..." (`"kind": "rename_foreign_key"` in
  `plan --json` and `diff --json`). On PostgreSQL, CockroachDB, YugabyteDB and H2 the
  migration renames the key (`RENAME CONSTRAINT`); other databases drop it and add it again,
  which checks every row.
- SQLite: the rollback of a table rename lost the other changes to that table (a renamed
  foreign key, a changed column). SQLite makes them by rebuilding the table, and the rollback
  looked the table up under the name it gives back only afterwards, so the rebuild was left
  out without a word. It is now rebuilt under that name, and a table that can't be found stops
  `migrax generate` with an error instead.
- Oracle: making a NUMBER column smaller (fewer decimals or fewer digits before the point,
  `bigint` to `integer` included) failed with ORA-01440 once the table had rows, in a
  migration or in the rollback of one that made it larger. Oracle allows that only on an
  empty column, so the change now goes through a new column: first a check that every value
  fits unchanged (a value with too many digits or decimals stops the migration with a message
  saying how many, before the column is changed, and so does an index, constraint or column
  grant made by hand that dropping the column would remove), then the new column is added, a
  trigger keeps it up to date while the application goes on writing, the values are copied
  and checked once more, the old column is dropped (writes fail for a moment there) and the
  new one gets its name, default, NOT NULL and comment. The table is rewritten and the column
  moves to its end; the migration account needs `CREATE TRIGGER`. Columns in a key, index,
  unique constraint or check, and identity columns, keep the plain change, which Oracle
  still refuses once they have values (see Reference > Limitations).

### Security

- Container image: newer JDBC drivers without known vulnerabilities. PostgreSQL 42.7.5 to
  42.7.14 (could fall back to weaker authentication despite `channelBinding=require`, and a
  server could make it spend CPU on SCRAM), MariaDB 3.5.1 to 3.5.11 (could send the password
  in clear text to a server in the middle), SQL Server 12.8.1 to 13.6.1 (did not check some
  input from the server well enough). URLs with `encrypt` and `trustServerCertificate` work
  as before (tested on SQL Server 2022). The CLI download bundles no drivers and is not affected.
- Container image: the base images are pinned to exact versions, so every build of a release
  ships the same thing, and `/usr/bin/pebble`, a program of the base image that Migrax never
  runs and that scanners report, is removed.
- The published POMs no longer name library versions with known vulnerabilities
  (Micrometer, OpenTelemetry, Spring Boot). Migrax never bundled them: your application keeps
  its own versions, and the framework versions Migrax works with are unchanged.
- The GitHub Action (`uses: fsmutimeer/migrax@...`) runs the actions it uses at exact
  commits instead of version tags, so a moved or compromised tag can't change what runs in
  your workflow.

## [0.17.0] - 2026-10-22

### Added

- Spring Data JDBC: projects without JPA that use Spring Data JDBC (3.0 to 4.1, Spring Boot 3.0
  to 4.1) get `generate`, `check`, `verify`, `drift` and the startup migration like JPA
  projects. Migrax reads Spring Data JDBC's own mapping, without a database: aggregate roots
  are the classes with `@Table` and the types of repositories, and their child tables get the
  back-reference and key columns Spring Data JDBC names (`@MappedCollection`), with a foreign
  key to the parent. Found by itself when the project has no JPA entities, or chosen with
  `--extractor spring-data-jdbc`. Names written in `@Table` or `@Column` keep their letter case
  where an unquoted name would get another one (lower case on H2, Oracle, HSQLDB and Derby,
  capitals on PostgreSQL), as Spring Data JDBC sends them quoted. See Reference >
  Entity readers.
- Micronaut Data JDBC and R2DBC: projects with `@MappedEntity` entities (Micronaut Data 4 and
  5) get `generate`, `check`, `verify` and `drift` like JPA projects. Migrax reads the mapping
  Micronaut's annotation processor compiled, without a database, and follows Micronaut Data's
  rules for names, join tables, embedded values and generated ids (identity, sequences, UUIDs).
  Found by itself when the project has no JPA entities, or chosen with
  `--extractor micronaut-data`. Micronaut 5 projects need Migrax running on Java 25; Migrax
  names the Java a project needs when it can't load its classes. See Reference > Entity
  readers.
- `migrax environments`: every environment's migrations side by side, read-only. For each
  `migrax.environments.<name>.url` (dev, staging, production, ...) it shows how many migrations
  are applied and pending, the last one applied and whether the schema drifted from the
  snapshot; `-- migrax:env` migrations count only where they run. All databases are asked at
  once with `--timeout`, so one that doesn't answer only marks its own row. Exit code 1 when an
  environment can't be reached or a migration failed, changed or is missing there, 2 on drift;
  `--json` for dashboards. Error `MXE116` when none is configured. See Guides > Comparing
  databases.
- Migrations from a SQL file instead of entities: with `migrax.schema-file=db/schema.sql`
  (`--schema-file`, `MIGRAX_SCHEMA_FILE`, or `migrax.databases.<name>.schema-file`), a project
  without JPA entities keeps its tables as they should be in one SQL file, and `generate`
  writes the migration and its rollback script from the changes, with rename questions, lint
  and `--zero-downtime` as for entities. `plan`, `check`, `verify` and `doctor` read the file
  too. Migrax reads it by applying it to a throwaway database (in memory, Docker, or an empty
  one given with `--scratch-url`) and keeps what it read in `.migrax/schema-model.json`, so
  `check` in CI needs no Docker while the file is unchanged. A statement that fails is named
  with its line (error `MXE115`). On PostgreSQL, MySQL and MariaDB the throwaway database runs
  the server's own version, so a file written by `pg_dump --schema-only` or
  `mysqldump --no-data` applies as it is. See Guides > SQL migrations for any language and
  Guides > Schema file from your ORM (SQLAlchemy, Drizzle, and any ORM through a dump).

### Fixed

- `migrax clean` drops tables and sequences whose names keep their letter case: names with
  capitals on PostgreSQL, or lower-case names created in quotes on H2 and Oracle. Before, it
  stopped with "table does not exist".
- The Spring Boot starter now works on Spring Boot 4. Spring Boot 4 moved the classes the
  starter was ordered against, so it didn't run at all: no migrations at startup, and a JPA
  application with `ddl-auto=validate` failed with a missing table. The `migrax` health
  component works with Spring Boot 4's Actuator too.
- `migrax-micronaut` now works on Micronaut 4.0 to 4.9. Before, those versions skipped it
  without an error: no migrations at startup, and the application failed on a missing table.
  Micronaut 4.10 and 5 work as before.

## [0.16.0] - 2026-10-19

### Added

- Drift at startup: `migrax.drift.check=warn` or `fail` makes the application compare the
  database with the schema its migrations produce when it starts, in the Spring Boot, Quarkus,
  Micronaut, Helidon and Jakarta EE integrations (`migrax.databases.<name>.drift.check` per
  database). `warn` logs every difference, `fail` stops the start; the default is `off`. The
  build puts `.migrax/snapshot.json` into the application: the Quarkus extension (also native)
  and the Gradle plugin do it by themselves, Maven with the new goal `migrax:package-snapshot`.
  See Guides > Production safety > Drift at startup.

### Changed

- `history` and `pending` can't be names of further databases (`migrax.databases.<name>`): the
  `migrax` command keeps the default database's files in `.migrax/history/` and
  `.migrax/pending/`, next to each further database's `.migrax/<name>/`.
- `migrax-integrations` and `migrax-quarkus-parent` are no longer published to Maven Central:
  they only group modules for the build, and no published artifact refers to them.

## [0.15.0] - 2026-10-19

### Added

- Several databases per application: a block `migrax.databases.<name>.*` (data source,
  folders, Java package, schemas, placeholders) migrates a further database at startup, in the
  Spring Boot, Quarkus (also native), Micronaut, Helidon and Jakarta EE integrations. Health
  checks report every database. See Guides > Several databases.
- `--database <name>` on every command that works on a project (and `-Dmigrax.database` for
  the Maven goals, `MIGRAX_DATABASE`): the command works on that database, with its own
  connection, folders, entity package (`migrax.databases.<name>.package`) and snapshot in
  `.migrax/<name>/`. The default database no longer reads the entities of another database's
  package.

### Changed

- Every metric has the tag `database` (`default`, or the name of a further database), and
  spans the attribute `migrax.database`. Queries without the tag keep working.
- A migration folder without a prefix (`migrax.locations=db/sql`, `--locations db/sql`) is now
  a classpath folder (under `src/main/resources`) for the `migrax` command and the Maven and
  Gradle goals too, the same as when the application migrates at startup. Before, the command
  looked for it in the project folder. Write `filesystem:db/sql` for a folder outside the
  resources; absolute paths are unchanged.

## [0.14.0] - 2026-10-18

### Added

- `migrax.migrate-at-startup=false` in the Spring Boot, Quarkus, Micronaut, Helidon and Jakarta
  EE integrations: the application starts without migrating, for teams whose pipeline or DBA
  runs the migrations, and logs what is pending; the health check keeps reporting.
- Gauge `migrax.migrations.pending` (`migrax_migrations_pending`): how many migrations wait to
  run, in the same integrations when the application has Micrometer. Off with
  `migrax.metrics.enabled=false`. See Guides > Metrics and tracing.
- `migrax diff <source-url>`: compares the schema of another database of the same kind (for
  example staging) with the project's database and lists the changes that would make it match;
  `--sql` prints them as a script, `--json` for tools, exit code 2 when they differ. Also
  `mvn migrax:diff`. See Guides > Comparing databases.
- GitLab CI and Bitbucket Pipelines templates: `check`, lint of new or changed migrations, the
  HTML report as an artifact, and a comment on the merge or pull request (updated on each
  pipeline). See Guides > Continuous integration.
- Published on Maven Central: the library and Maven plugin `org.migrax:migrax`, the framework
  integrations, `migrax-junit` and the Gradle plugin `org.migrax`, with sources, javadoc and
  signatures. Builds no longer need to install Migrax from the source first.

### Changed

- Migrax is now licensed under the Apache License 2.0 (with a `NOTICE` file). Releases up to
  0.13.0 keep the MIT License they were published with.

### Fixed

- `generate` no longer drops and re-adds a check constraint whose condition differs only in
  spacing or in parentheses around the whole condition (databases store conditions their own
  way, for example Derby).
- Check conditions read from MySQL keep valid SQL quotes (MySQL reports `''` as `\'\'`), and
  MySQL, MariaDB and TiDB primary keys are no longer named `PRIMARY` when Migrax writes them
  back.

## [0.13.0] - 2026-10-16

### Added

- Metrics and tracing of migrations at startup: Micrometer metrics (`migrax.run`,
  `migrax.migration`, `migrax.migrations.applied`/`failed`, `migrax.lock.wait`) and
  OpenTelemetry spans (`migrax migrate` with a `migrax migration` span each), recorded by the
  Spring Boot, Quarkus, Micronaut, Helidon and Jakarta EE integrations when the application has
  a `MeterRegistry` or an `OpenTelemetry`. Turn them off with `migrax.metrics.enabled=false` or
  `migrax.tracing.enabled=false`. `MigrationListener` beans hear every run too, and the Java API
  takes listeners with `listener(...)`. See Guides > Metrics and tracing.
- Preconditions: a `-- migrax:require` line makes a migration check the database before it
  runs (`table-exists`, `table-missing`, `column-exists`, `column-missing`, or `sql <query>`).
  An unmet condition stops the run (MXE114), or with `else skip` records the migration without
  running it. `status` and `migrate --dry-run` show the conditions of pending migrations;
  `migrate --sql` checks them for the first migration and stops before later ones.

## [0.12.0] - 2026-10-16

Four more databases: YugabyteDB, TiDB, HSQLDB and Apache Derby.

### Added

- YugabyteDB (2024.2 and newer), the 9th database: dialect `yugabytedb`, reached with the
  PostgreSQL driver (recognized from the server) or YugabyteDB's own driver. It writes
  PostgreSQL's SQL; the migration lock is PostgreSQL's advisory lock from 2025.1 on and a row
  in `migrax_lock` before. `verify` starts a throwaway YugabyteDB container, and
  `--zero-downtime` works as on PostgreSQL.
- TiDB (7.5 and newer), the 10th database: dialect `tidb`, reached with the MySQL driver and
  recognized from the server. It writes MySQL's SQL; check constraints need
  `tidb_enable_check_constraint` (`migrax doctor` warns when it is off), `--keep-data` backs up
  with `CREATE TABLE ... LIKE`, and `--zero-downtime` can't split renames (no triggers or
  writable views). `verify` starts a throwaway TiDB container.
- HSQLDB (HyperSQL 2.7 and newer), the 11th database: dialect `hsqldb`, recognized from
  `jdbc:hsqldb:` URLs. The migration lock is a row in `migrax_lock`, so it also holds across
  processes for file and server databases; `verify` replays the migrations in memory.
- Apache Derby (10.16 and newer), the 12th database: dialect `derby`, recognized from
  `jdbc:derby:` URLs. Type changes Derby can't make in place rebuild the column, comments are
  left out (Derby keeps none), and `verify` replays the migrations in memory.

## [0.11.0] - 2026-10-16

Zero-downtime changes and an HTML report.

### Added

- `generate --zero-downtime` splits a change so the old and the new application version can
  use the database at the same time: an expand migration for this release (new columns still
  nullable, a renamed column copied and kept equal to the old one by a trigger, a renamed
  table's old name kept as a view, wider types) and a contract migration for the next release
  (drops, NOT NULL, narrower types, new constraints). The contract migration waits in
  `.migrax/pending/` until `generate --contract` adds it to the migration folder. Column
  renames need PostgreSQL, MySQL, MariaDB or Oracle; table renames also work on SQL Server.
  See Guides > Zero-downtime changes.
- `migrax report` (and `mvn migrax:report`) writes one HTML page for reviews and CI
  artifacts: the schema with a diagram, entity changes without a migration, every migration
  with its status and lint findings, and drift. The GitHub Action uploads it as the artifact
  `migrax-report` and links it from its pull request comment (`report: false` turns it off).

### Changed

- Lint rules MX007 (drops) and MX008 (renames) name `generate --zero-downtime` as the fix.

## [0.10.0] - 2026-10-16

A Java API, and a JUnit 5 extension that checks migrations against the entities.

### Added

- A Java API, `org.migrax.api.Migrax`: `Migrax.configure().dataSource(ds).load()`, then
  `migrate()`, `status()`, `rollback(steps)`, `rollbackTo(version)`, `validate()` (do the
  entities match the database?) and `testRollbacks()`. The startup integrations run on it.
- `migrax-junit`, a JUnit 5 extension: `@MigraxTest` migrates a test database (H2, a
  `DataSource` or a Testcontainers container) before the tests and fails them when the
  entities don't match the migrated schema. With `rollbacks = true` it also tests every
  rollback script. See Guides > Java API and JUnit tests.

## [0.9.0] - 2026-10-15

Every rollback script tested on its own, and Oracle 23ai support.

### Changed

- `verify` tests every rollback script on its own: it rolls the migration back, applies it
  again and checks that the schema is the same, and it names the script when that fails.
  Before, it only checked the end result, which missed a script that left something behind
  when an older script removed it anyway (an index on a table dropped later).

### Fixed

- On Oracle 23ai, `boolean` fields become `boolean` columns, as Hibernate expects there,
  instead of `number(1,0)`. Migrax asks the database for its version and records it in the
  snapshot for commands that run without a connection. Older Oracle versions keep
  `number(1,0)`.
- `verify` without `--url` works on Oracle: the throwaway container's port was never
  published, so it could not connect. It now starts Oracle Free 23ai for a 23ai project and
  Oracle XE 21 otherwise, with its own user instead of `system`.
- `verify` on Oracle no longer reports Hibernate's temporary tables (`hte_...`) as left
  behind after rolling everything back: schema validation doesn't create them anymore.
- With Oracle's JDBC driver 23, a failed connection no longer prints the driver's trace (a
  stack trace with the `PATH` and Java settings) to the console; `--verbose` still shows it.

## [0.8.0] - 2026-10-15

Hibernate 4.0 and 4.1 read through their own mapping, and installs with Homebrew and Scoop.

### Added

- Hibernate 4.0 and 4.1 projects are read through Hibernate's own mapping, like 4.2 and newer,
  and `verify` has them validate the schema. Both versions are tested on every change.
- Install with Homebrew on macOS and Linux (`brew install fsmutimeer/migrax/migrax`) or with
  Scoop on Windows (`scoop bucket add migrax https://github.com/fsmutimeer/scoop-migrax`, then
  `scoop install migrax`). Every stable release updates both.

### Changed

- The Migrax GitHub Action uses `actions/setup-java@v6` and `actions/github-script@v8`, which
  run on Node 24, so workflows that use it no longer show Node 20 deprecation warnings.

## [0.7.0] - 2026-10-15

The 0.7 release removes three more limitations: `--schemas` works on SQL Server, `--keep-data`
keeps data of tables without a primary key, and Hibernate 4.2 and 4.3 are read through their
own mapping.

### Added

- `--schemas` works on SQL Server. SQL Server can't switch a connection to another schema, so
  Migrax names the schema instead: each schema gets its own `migrax_history`, and in a project
  that sets `migrax.schemas`, `generate` writes every table as `${migrax.schema}.<table>`.
  `migrate`, `status`, `rollback`, `migrate --sql`, `clean` and the startup integrations all
  work per schema.
- `${migrax.schema}`: a built-in placeholder for the schema a migration runs in, on every
  database.
- Lint rule MX017: on SQL Server with `migrax.schemas`, a table without its schema in front.
- `generate --keep-data` keeps dropped and narrowed columns of tables without a primary key.
  It finds the rows again by a unique key whose columns are NOT NULL, or else by all their other
  columns; `generate` warns in that case, because rows that are the same in every other column
  get one value back for all of them.
- Hibernate 4.2 and 4.3 are read through their own mapping, like Hibernate 5 and newer, and
  `verify` has them validate the schema. Spring Boot 1's `spring.jpa.hibernate.naming-strategy`
  is read too. Older versions are still read from their annotations.

## [0.6.1] - 2026-10-14

### Fixed

- Oracle packages, package and type bodies, and anonymous blocks (`BEGIN ... END;`,
  `DECLARE ... BEGIN ... END;`) no longer need a line with only `/` after them: Migrax finds
  where they end, as it already did for procedures, functions and triggers.
- PostgreSQL and CockroachDB: a `migrate --sql` script run with psql recorded `applied_at` in
  the server's time zone, so `migrax status` could show it hours before the migrations Migrax
  applied itself. The script now records the time in the same zone as Migrax.
- `verify`: when a migration fails on the throwaway database, the error says that the migration
  fails on an empty database and what to check. It used to give the advice for a real database
  (inspect it, repair, resume), which doesn't apply there.

## [0.6.0] - 2026-10-14

The 0.6 release removes most of Migrax's limitations: EclipseLink and OpenJPA are read through
their own mapping like Hibernate (now from 5.0), Quarkus migrates before Hibernate and in native
executables, Jakarta EE servers migrate their own data sources, and `--keep-data` rollbacks
restore dropped data.

### Added

- Quarkus: `migrax-quarkus` is now a Quarkus extension. It applies the migrations before
  Hibernate starts, so `quarkus.hibernate-orm.schema-management.strategy=validate` works, and it
  runs in native executables. The dependency stays the same; Quarkus fetches the new
  `migrax-quarkus-deployment` artifact itself. The readiness check is added only when the
  application has the health extension, so the build warning about `HealthCheck` is gone.
  Migrations are listed when the application is built: set `migrax.locations` before the build.
- `generate --keep-data` keeps the data a migration drops, and its rollback script restores it:
  a dropped table is renamed to a `migrax_backup_*` table and renamed back, and a dropped or
  narrowed column is copied there with the table's primary key and filled again row by row.
  The new `migrax backups` command lists the backup tables and drops them (`--drop`, `--yes`).
  Tested on all 8 databases.
- OpenJPA: projects that use OpenJPA (Apache TomEE's JPA provider) are read through OpenJPA's
  own mapping too: its names, the indexes it puts on foreign keys, identity columns, sequences
  and `OPENJPA_SEQUENCE_TABLE`. Tested with OpenJPA 3.2 and 4.1, and on PostgreSQL and MySQL;
  `--extractor openjpa` forces it.
- EclipseLink: projects that use EclipseLink are read through EclipseLink's own mapping, as
  Hibernate projects are through Hibernate's: its names, its join and collection tables, and
  its id strategies, including the `SEQUENCE` table and rows a plain `@GeneratedValue` needs.
  Before, they were read with Hibernate's rules and EclipseLink failed at runtime. Tested with
  EclipseLink 2.7, 3.0 and 4.0, and on PostgreSQL and MySQL. `--extractor eclipselink` forces
  it. The database reader also recognizes H2's `BINARY LARGE OBJECT` and `CHARACTER LARGE
  OBJECT` columns, which made the first `generate` change `@Lob` columns on H2.
- Jakarta EE: the new `migrax-jakarta-ee` integration applies the migrations when the
  application starts on the server, to the data source `persistence.xml` names as the server
  provides it (or `migrax.datasource`), so the connection isn't configured twice. Tested on
  WildFly and Payara. Migrations and Java migrations are also found in WildFly's virtual file
  system.
- Hibernate 5.0 to 5.3 are read through their own mapping and validate the schema in
  `verify`, like 5.4 and newer, instead of annotation scanning. 5.0 to 5.2 need JAXB in the
  project on Java 11 and newer; without it Migrax falls back as before and says what is
  missing. The Hibernate compatibility tests now cover 19 versions.
- SQL Server migrations can repeat a batch with `GO 5`, as in SQL Server's own tools.
- `migrate --sql` works with `--schemas`: the script has a section per schema that switches to
  it first. When a Java migration is pending, the script now stops before it and says how to
  go on, instead of refusing to write anything.
- CockroachDB: a migration that changes a column type in a way CockroachDB can't do in place
  (anything but widening) turns on CockroachDB's setting for it and runs outside a
  transaction, as CockroachDB requires. Before, such migrations failed until edited by hand.
- `migrax doctor` warns when the entities declare check constraints and the server is MySQL
  before 8.0.16, which accepts them without enforcing them.
- Procedures, functions and triggers no longer need `GO`, `DELIMITER` or `/` lines: Migrax ends
  the statement with the `END` that closes the body's first `BEGIN`. Scripts with those lines
  work as before. New lint rule MX016 warns when a body has an unmatched `BEGIN` and so takes
  in the statements after it.

### Fixed

- The first `generate` on an existing database saw a fixed-length `BINARY(n)` column as
  `varbinary` and wrote a change for it, for example for the `binary(255)` columns Hibernate 5
  writes on H2. The database reader now keeps the column's real type.
- On a database Hibernate created, an `@Check` without a name was added a second time by the
  first `generate`, because the database had given the existing check a name of its own.
  Migrax now recognizes the check by its condition and keeps the database's name.

## [0.5.0] - 2026-10-14

The 0.5 release: check constraints and comments in generated migrations, stored procedures and
triggers in SQL migrations, and more control over what `migrate` runs and where.

### Added

- `generate` writes check constraints and comments: Hibernate's `@Check`, `@Checks` and
  `@Comment`, and the `check` and `comment` attributes of Jakarta Persistence 3.2's `@Table`
  and `@Column`, on all 8 databases (SQLite keeps no comments). A changed condition drops the
  old check and adds the new one, and `--safe` adds checks on PostgreSQL and CockroachDB
  without blocking writes. The checks Hibernate 6+ derives from enum columns are generated only
  with `migrax.enum-checks=true`. Projects that already use these annotations get them in their
  next generated migration; remove any that a hand-written migration added before.
- `migrate --sql` (Maven: `-Dmigrax.sql=true`) prints one SQL script with everything `migrate`
  would do, for a DBA to review and run with `psql`, `mysql`, `sqlcmd` or `sqlplus`: the
  pending migrations, the history rows Migrax records, transactions where the database has
  them, and each tool's end markers. Nothing changes until the script runs.
- Migrations for some environments only: a `-- migrax:env dev,test` line makes a migration
  run only where `--env`, `MIGRAX_ENV` or `migrax.env` names one of them. Elsewhere `status`
  shows it as skipped (`[-]`, state `SKIPPED` in `--json`), and health checks don't count it
  as pending.
- Several migration folders: `--locations`, `MIGRAX_LOCATIONS` and `migrax.locations` take a
  comma-separated list (CLI, Maven, Gradle and the startup integrations). Files run in version
  order across all folders; new files go into the first one.
- `migrax baseline <migration> --yes` (Maven: `migrax:baseline`) adopts a database whose
  tables already exist: it records the migrations up to the named one as applied without
  running them, so `migrate` only runs the later ones.
- `migrate --to <migration>` (Maven: `-Dmigrax.to=...`) applies versioned migrations only up
  to and including the named one, for staged rollouts. The name can be the file name, its
  start, or the version number (`--to 5`). Repeatable migrations wait for the next migrate
  without `--to`.
- Migrations can contain stored procedures, functions and triggers: a line with only `GO` ends
  a SQL Server batch, `DELIMITER` lines change the MySQL/MariaDB delimiter, and a line with only
  `/` ends an Oracle PL/SQL block (also usable for SQLite triggers). `-- migrax:no-split` sends
  a whole file as one statement. `migrate`, `rollback`, `lint` and `verify` all split this way.
- Guides > Without Java entities: how to apply hand-written SQL migrations with Migrax in
  projects without JPA entities (Python, Node.js, other languages): drivers, connection
  settings, the container image and rollback scripts.

### Changed

- The documentation moved to https://migrax.org/; the old address
  https://docs-migrax.github.io/ redirects there. The site has a new black, white and yellow
  look.

### Fixed

- `migrax doctor` no longer fails in projects without Java, such as the container image or a
  Python or Node.js project with hand-written migrations: when there is no `pom.xml`,
  `build.gradle` or entity package but there are migrations, it checks only the migrations
  and the database. Without migrations it still reports the missing build file, so a Java
  project opened in the wrong folder is still caught.

## [0.4.0] - 2026-10-11

The 0.4 release: Migrax moves to `org.migrax`. Nothing else changes in how it works; see
below for the one-line upgrade.

### Changed

- Migrax now lives under `org.migrax` (the project's domain is migrax.org): the Maven
  groupId, the Gradle plugin ID and the Java packages all change from `io.migrax` to
  `org.migrax`. Commands, options, configuration keys, environment variables, migration files,
  the snapshot and the database tables are unchanged. The migration lock has a new name too,
  so a 0.3 and a 0.4 instance don't wait for each other: during the upgrade, make sure only
  one of them runs migrations against a database at a time.

  To upgrade, replace `io.migrax` with `org.migrax` in your build:

  - Maven: `<groupId>org.migrax</groupId>` for the plugin and the integrations.
  - Gradle: `plugins { id("org.migrax") version "..." }` and `org.migrax:...` dependencies.
  - Java migrations: `import org.migrax.api.JavaMigration;`.
  - Logging configuration: the logger is now `org.migrax`.

## [0.3.0] - 2026-10-11

The 0.3 release: Migrax in containers and Kubernetes, machine-readable output, and AI
assistants. It contains everything in the release candidates 0.3.0-rc.1 to 0.3.0-rc.3; the
changelog lists each of their changes in detail.

### Added

- `migrax mcp`: a Model Context Protocol server for AI assistants (Claude Code, Codex CLI,
  Gemini CLI, GitHub Copilot in VS Code, Cursor, ...). Its tools only read; `--allow-generate`
  also lets it write migration files. It never changes a database.
- The documentation as plain text for AI assistants: `llms.txt` and `llms-full.txt`.
- Container image `ghcr.io/fsmutimeer/migrax` (amd64 and arm64) with the common JDBC drivers,
  and Guides > Containers and Kubernetes with tested example manifests.
- `--lock-timeout <time>`: wait for the migration lock instead of failing, for instances that
  start together.
- Database secrets from files (`MIGRAX_DATABASE_PASSWORD_FILE`, `--password-file`, ...) and
  JDBC drivers without a project (`MIGRAX_DRIVERS` or the installation's `drivers` folder).
- `--json` on every command, with failures as JSON too, and stable error codes (`MXE...`)
  listed in Reference > Errors and JSON output.
- Health checks in the Spring Boot, Quarkus, Micronaut and Helidon integrations.

### Changed

- Error lines start with `error[<code>]:` instead of `error:`.
- Guides > Using Migrax with AI assistants: setup for Codex CLI, Gemini CLI, GitHub Copilot in
  VS Code and Cursor, how to start `migrax mcp` through `cmd` on Windows for clients that can't
  run `.cmd` scripts, and why the ChatGPT app can't use a local MCP server.

## [0.3.0-rc.3] - 2026-10-10

Third release candidate for 0.3.0, for testing. It contains everything in 0.3.0-rc.2 and
these changes: Migrax for AI assistants.

### Added

- `migrax mcp`: a Model Context Protocol server, so AI assistants such as Claude Code can call
  status, check, plan, lint, drift, doctor, verify and show_migration as tools. It never
  changes a database; `--allow-generate` also lets it write migration files. Guide: Guides >
  Using Migrax with AI assistants, with instructions to copy into a project's `AGENTS.md` or
  `CLAUDE.md`.
- The documentation as plain text for AI assistants: `llms.txt` and `llms-full.txt` on the
  docs site.

## [0.3.0-rc.2] - 2026-10-10

Second release candidate for 0.3.0, for testing. It contains everything in 0.3.0-rc.1 and
these changes: machine-readable results and errors, and health checks.

### Added

- `--json` for `migrate`, `rollback`, `repair`, `clean`, `doctor`, `new`, `squash` and `merge`:
  one result object with `ok` and `exitCode` on standard output, progress on standard error.
  With `--json`, a failure is also a JSON object.
- Stable error codes (`error[MXE104]: ...`), listed in Reference > Errors and JSON output.
- Health checks in the startup integrations: a `migrax` health indicator for Spring Boot
  Actuator and Micronaut, and a readiness check for Quarkus and Helidon; UP when every
  migration is applied and none failed, was edited or is missing. Each is active only when the
  application has the framework's health module.

### Changed

- Error lines start with `error[<code>]:` instead of `error:`.

## [0.3.0-rc.1] - 2026-10-10

First release candidate for 0.3.0, for testing: running Migrax in containers and Kubernetes.

### Added

- Container image `ghcr.io/fsmutimeer/migrax:<version>` (amd64 and arm64) with Migrax and the
  PostgreSQL, MariaDB, SQL Server, SQLite and H2 drivers, for running migrations without a
  Java project. Guide: Guides > Containers and Kubernetes, with example manifests (Job, init
  container, Helm hook, nightly drift CronJob) tested on a local Kubernetes cluster.
- `--lock-timeout <time>` (`MIGRAX_LOCK_TIMEOUT`, `migrax.lock-timeout` in the startup
  integrations): wait while another process holds the migration lock instead of failing, for
  instances that start together.
- Database secrets from files: `MIGRAX_DATABASE_PASSWORD_FILE` (and `_USER_FILE`, `_URL_FILE`)
  and `--password-file`, for Kubernetes and Docker secrets.
- JDBC drivers without a project: every jar in `MIGRAX_DRIVERS` or the installation's
  `drivers` folder is available, after the project's own dependencies.

## [0.2.0] - 2026-10-10

The 0.2 release: two more databases, a command to start a development database over, and the
fixes from 0.1.1 to 0.1.4. It contains everything in the release candidates 0.2.0-rc.1 to
0.2.0-rc.6; the changelog lists each of their changes in detail.

### Added

- CockroachDB support (`cockroachdb`), through the PostgreSQL driver: Migrax recognizes the
  server by itself, and `verify` starts a throwaway CockroachDB in Docker.
- SQLite support (`sqlite`, SQLite 3.35 and newer): changes SQLite can't make in place
  rebuild the table with its rows; `verify` runs on a temporary SQLite file.
- `migrax clean` (and `mvn migrax:clean`) drops every table, view and sequence so `migrate`
  can rebuild a development database; `MIGRAX_CLEAN_DISABLED=true` turns it off.
- `migrax repair <migration>... --action forget --yes` removes applied migrations whose files
  were deleted on purpose.
- The first `generate` against a database that already has tables also writes those tables
  to `0001_baseline.sql`, so an empty database can be built from the migrations.
- Documentation for `migrax import flyway|liquibase` (Guides > Switching from Flyway or
  Liquibase), and a Download page with every release's files and SHA-256 checksums.
- MIT license.

### Changed

- `migrax doctor` checks locking by taking and releasing the migration lock.
- Each database's support, including its migration lock, is one class found with
  `java.util.ServiceLoader`; the CLI is one class per command. Commands and output are
  unchanged.

### Fixed

- The first `generate` against an existing database no longer finds changes that aren't real,
  on every supported database and with both ways of reading entities.
- No JVM crash with Java 21 and Hibernate 7.3 or newer.
- `migrax import liquibase` includes the entities' sequences in its baseline.

## [0.2.0-rc.6] - 2026-10-10

Sixth release candidate for 0.2.0, for testing. It contains everything in 0.2.0-rc.5 and
these changes.

### Added

- CockroachDB support (`cockroachdb`), through the PostgreSQL driver: Migrax recognizes the
  server, writes CockroachDB's types, takes its migration lock as a row in `migrax_lock`, and
  `verify` starts a throwaway CockroachDB in Docker. Tested with the real-database suite and
  Hibernate's schema validation.
- SQLite support (`sqlite`, SQLite 3.35 and newer): changes SQLite can't make in place, such
  as a column's type or a new foreign key, rebuild the table with its rows, and a rebuild that
  breaks a foreign key fails before it commits. `verify` runs on a temporary SQLite file.
- `migrax clean` (and `mvn migrax:clean`): drops every table, view and sequence, the migration
  history included, so `migrate` can rebuild a development database. It asks first (or needs
  `--yes`), `--dry-run` lists what it would drop, and `MIGRAX_CLEAN_DISABLED=true` turns it off.
- Documentation for `migrax import flyway|liquibase`, with a guide for switching an existing
  project (Guides > Switching from Flyway or Liquibase).

### Fixed

- `migrax import liquibase` left the entities' sequences out of the baseline, so a database
  built from it failed Hibernate's validation; it now reads the database as the first
  `generate` does.
- Hibernate's mapping of a `@Lob String` as `varchar(255)` (CockroachDB) is read as text, so
  long values aren't cut off.
- Hibernate's bulk-update helper tables (`HTE_<table>`) are no longer seen as user tables.

## [0.2.0-rc.5] - 2026-10-10

Fifth release candidate for 0.2.0, for testing. It contains everything in 0.2.0-rc.4 and
these fixes (also released for 0.1 as 0.1.4).

### Fixed

- The first `generate` against a database that already has tables wrote only the differences,
  so an empty database couldn't be built from the migrations and `migrax verify` failed on the
  first one ("table doesn't exist"). It now also writes those tables to `0001_baseline.sql`
  and records it as applied in that database without running it; the changes follow as
  `0002_...`. Projects that already have migrations are not changed.
- H2: the first `generate` against an existing database wanted to drop the indexes H2 creates
  for foreign keys (named like `fk_..._INDEX_8`) and refused as destructive. They are now
  recognized as part of the foreign key, as on MySQL and MariaDB.

## [0.2.0-rc.4] - 2026-10-09

Fourth release candidate for 0.2.0, for testing. It contains everything in 0.2.0-rc.3 and
these fixes (also released for 0.1 as 0.1.3).

### Fixed

- More changes that weren't real in the first `generate` against an existing database, found
  by a new test that runs it on MySQL (also with Windows-style lower-case table names),
  MariaDB, PostgreSQL, SQL Server and Oracle, reading entities both through Hibernate and by
  annotation scanning:
  - PostgreSQL: ids that take their value from a sequence were seen as identity columns, and
    the identity was dropped;
  - SQL Server: `varchar(max)` and `varbinary(max)` columns were altered to themselves;
  - MySQL: UUID columns (`binary(16)`) were altered to themselves; UUID columns are now kept
    as they are, whether stored natively or as binary;
  - `numeric` and `decimal` are treated as the same type.

## [0.2.0-rc.3] - 2026-10-09

Third release candidate for 0.2.0, for testing. It contains everything in 0.2.0-rc.2 and
these changes (also released for 0.1 as 0.1.2).

### Added

- `migrax repair <migration>... --action forget --yes` removes applied migrations whose files
  were deleted on purpose from the history; the database keeps their changes. The "files are
  missing" error now shows the exact command.

### Fixed

- The first `generate` of a project, which compares the entities with the live database, no
  longer writes changes that aren't real:
  - tables whose names differ only in letter case (`categories_seq` in the database,
    `categories_SEQ` in the entity) on databases that fold names, such as MySQL on Windows,
    PostgreSQL, H2 and Oracle; this produced a `CREATE` and a `DROP` of the same table;
  - the index MySQL and MariaDB create for each foreign key, which was dropped;
  - sequences that already exist, which were created again: real sequences, and the
    `next_val` tables MySQL uses instead (sequences the entities don't use, such as those of
    identity columns, are never touched);
  - columns whose types the database stores the same way, such as an `Instant` field on MySQL
    (`datetime(6)` either way).
- `migrate` with an empty migration folder now reports applied migrations whose files were
  deleted, instead of saying there is nothing to migrate.
- On Windows, every command printed the path of the old class-data archive
  (`...\migrax\cache\migrax-java21.jsa`) and left the file in place: Java creates it read-only.
  The launcher now removes it silently.

## [0.2.0-rc.2] - 2026-10-09

Second release candidate for 0.2.0, for testing. It contains everything in 0.2.0-rc.1 and
this fix.

### Fixed

- Migrax crashed (a JVM crash, not an error message) when reading Hibernate 7.3 or newer entities
  on Java 21. A Java 21 bug (JDK-8391430) breaks the class-data archive the launcher keeps to
  start faster, so the launcher now keeps it only on Java 25 and newer, and removes archives
  that earlier versions left behind.

## [0.2.0-rc.1] - 2026-10-09

Release candidate for 0.2.0, for testing.

### Added

- MIT license.
- Download page on the documentation site, hosting every release's files with SHA-256
  checksums, updated automatically after each release.
- Database dialects are found with `java.util.ServiceLoader`
  (`META-INF/services/io.migrax.dialect.Dialect`), and each dialect brings its own migration
  lock, so a new database needs no change to existing code.

### Changed

- `migrax doctor` checks locking by taking and releasing the migration lock, so a missing
  permission (for example EXECUTE on DBMS_LOCK on Oracle) shows up before `migrate`.
- The CLI is split into one class per command; the commands and their output are unchanged.
  `CONTRIBUTING.md` explains how to add a command or a database.
- The Download page lists pre-releases in their own section, for testing.

## [0.1.4] - 2026-10-10

### Fixed

- The first `generate` against a database that already has tables wrote only the differences,
  so an empty database couldn't be built from the migrations and `migrax verify` failed on the
  first one ("table doesn't exist"). It now also writes those tables to `0001_baseline.sql`
  and records it as applied in that database without running it; the changes follow as
  `0002_...`. Projects that already have migrations are not changed.
- H2: the first `generate` against an existing database wanted to drop the indexes H2 creates
  for foreign keys (named like `fk_..._INDEX_8`) and refused as destructive. They are now
  recognized as part of the foreign key, as on MySQL and MariaDB.

## [0.1.3] - 2026-10-09

### Fixed

- More changes that weren't real in the first `generate` against an existing database, found
  by a new test that runs it on MySQL (also with Windows-style lower-case table names),
  MariaDB, PostgreSQL, SQL Server and Oracle, reading entities both through Hibernate and by
  annotation scanning:
  - PostgreSQL: ids that take their value from a sequence were seen as identity columns, and
    the identity was dropped;
  - SQL Server: `varchar(max)` and `varbinary(max)` columns were altered to themselves;
  - MySQL: UUID columns (`binary(16)`) were altered to themselves; UUID columns are now kept
    as they are, whether stored natively or as binary;
  - `numeric` and `decimal` are treated as the same type.

## [0.1.2] - 2026-10-09

### Added

- MIT license.
- Download page on the documentation site, hosting every release's files with SHA-256
  checksums.
- `migrax repair <migration>... --action forget --yes` removes applied migrations whose files
  were deleted on purpose from the history; the database keeps their changes. The "files are
  missing" error now shows the exact command.

### Fixed

- The first `generate` of a project, which compares the entities with the live database, no
  longer writes changes that aren't real:
  - tables whose names differ only in letter case (`categories_seq` in the database,
    `categories_SEQ` in the entity) on databases that fold names, such as MySQL on Windows,
    PostgreSQL, H2 and Oracle; this produced a `CREATE` and a `DROP` of the same table;
  - the index MySQL and MariaDB create for each foreign key, which was dropped;
  - sequences that already exist, which were created again: real sequences, and the
    `next_val` tables MySQL uses instead (sequences the entities don't use, such as those of
    identity columns, are never touched);
  - columns whose types the database stores the same way, such as an `Instant` field on MySQL
    (`datetime(6)` either way).
- `migrate` with an empty migration folder now reports applied migrations whose files were
  deleted, instead of saying there is nothing to migrate.
- On Windows, every command printed the path of the old class-data archive
  (`...\migrax\cache\migrax-java21.jsa`) and left the file in place: Java creates it read-only.
  The launcher now removes it silently.

## [0.1.1] - 2026-10-09

### Fixed

- The `migrax` command crashed (a JVM crash, not an error message) when reading Hibernate 7.3 or
  newer entities on Java 21. A Java 21 bug (JDK-8391430) breaks the class-data archive the
  launcher keeps to start faster, so the launcher now keeps it only on Java 25 and newer, and
  removes archives that 0.1.0 left behind. The Maven plugin and the framework integrations were
  not affected.

## [0.1.0] - 2026-10-08

First release.

### Added

- **Generated migrations.** `migrax generate` compares JPA entities with a committed snapshot
  and writes a numbered SQL migration with a rollback script. `plan` previews it.
- **Hibernate's own mapping.** With Hibernate 5.4 to 7.4 in the project, entities are read
  through that Hibernate version, so names, types, join tables, element collections,
  inheritance and sequences match exactly. Annotation scanning covers projects without it.
- **Safe changes.** Rename detection with a confirmation prompt or `--rename`; drops only with
  `--allow-destructive`; `generate --safe` for non-blocking PostgreSQL indexes and constraints.
- **Runner.** `migrate`, `status`, `rollback`, `repair`; checksums of applied migrations,
  database locks, failure tracking, repeatable migrations, callbacks, placeholders, Java
  migrations and multi-tenant schemas.
- **Checks.** `check` for CI, `lint` with 15 rules for locking and data-loss risks, `verify`
  (migrations on a throwaway database validated by your Hibernate version, with rollback
  round trips) and `drift` (manual changes in the live database, including narrowed columns).
- **Team workflow.** `merge` for migrations two branches numbered the same, `squash`, and `new`
  for hand-written SQL or Java migrations.
- **Frameworks.** Configuration, naming and profile detection for Spring Boot, Quarkus,
  Micronaut 4 and 5 (including Micronaut Data naming), Helidon MP and SE, and Jakarta EE /
  plain Hibernate.
- **Integrations.** Startup migration for Spring Boot, Quarkus, Micronaut and Helidon; Maven
  and Gradle plugins; a GitHub Action that checks, lints and comments the planned SQL.
- **Databases.** PostgreSQL, MySQL 8, MariaDB, SQL Server, Oracle 12c+ and H2.
- **Diagnostics.** `doctor` checks the setup and explains risky Hibernate schema settings with
  the file, line and exact fix.
- **Command line.** Installers for Windows, macOS and Linux; works from any service folder
  without configuration.

[Unreleased]: https://github.com/fsmutimeer/migrax/compare/v0.17.1...HEAD
[0.17.1]: https://github.com/fsmutimeer/migrax/compare/v0.17.0...v0.17.1
[0.17.0]: https://github.com/fsmutimeer/migrax/compare/v0.16.0...v0.17.0
[0.16.0]: https://github.com/fsmutimeer/migrax/compare/v0.15.0...v0.16.0
[0.15.0]: https://github.com/fsmutimeer/migrax/compare/v0.14.0...v0.15.0
[0.14.0]: https://github.com/fsmutimeer/migrax/compare/v0.13.0...v0.14.0
[0.13.0]: https://github.com/fsmutimeer/migrax/compare/v0.12.0...v0.13.0
[0.12.0]: https://github.com/fsmutimeer/migrax/compare/v0.11.0...v0.12.0
[0.11.0]: https://github.com/fsmutimeer/migrax/compare/v0.10.0...v0.11.0
[0.10.0]: https://github.com/fsmutimeer/migrax/compare/v0.9.0...v0.10.0
[0.9.0]: https://github.com/fsmutimeer/migrax/compare/v0.8.0...v0.9.0
[0.8.0]: https://github.com/fsmutimeer/migrax/compare/v0.7.0...v0.8.0
[0.7.0]: https://github.com/fsmutimeer/migrax/compare/v0.6.1...v0.7.0
[0.6.1]: https://github.com/fsmutimeer/migrax/compare/v0.6.0...v0.6.1
[0.6.0]: https://github.com/fsmutimeer/migrax/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/fsmutimeer/migrax/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/fsmutimeer/migrax/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/fsmutimeer/migrax/compare/v0.2.0...v0.3.0
[0.3.0-rc.3]: https://github.com/fsmutimeer/migrax/compare/v0.3.0-rc.2...v0.3.0-rc.3
[0.3.0-rc.2]: https://github.com/fsmutimeer/migrax/compare/v0.3.0-rc.1...v0.3.0-rc.2
[0.3.0-rc.1]: https://github.com/fsmutimeer/migrax/compare/v0.2.0...v0.3.0-rc.1
[0.2.0]: https://github.com/fsmutimeer/migrax/compare/v0.1.0...v0.2.0
[0.2.0-rc.6]: https://github.com/fsmutimeer/migrax/compare/v0.2.0-rc.5...v0.2.0-rc.6
[0.2.0-rc.5]: https://github.com/fsmutimeer/migrax/compare/v0.2.0-rc.4...v0.2.0-rc.5
[0.2.0-rc.4]: https://github.com/fsmutimeer/migrax/compare/v0.2.0-rc.3...v0.2.0-rc.4
[0.2.0-rc.3]: https://github.com/fsmutimeer/migrax/compare/v0.2.0-rc.2...v0.2.0-rc.3
[0.2.0-rc.2]: https://github.com/fsmutimeer/migrax/compare/v0.2.0-rc.1...v0.2.0-rc.2
[0.2.0-rc.1]: https://github.com/fsmutimeer/migrax/compare/v0.1.0...v0.2.0-rc.1
[0.1.4]: https://github.com/fsmutimeer/migrax/compare/v0.1.3...v0.1.4
[0.1.3]: https://github.com/fsmutimeer/migrax/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/fsmutimeer/migrax/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/fsmutimeer/migrax/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/fsmutimeer/migrax/releases/tag/v0.1.0
