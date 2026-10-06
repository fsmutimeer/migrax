# Migrax 0.1.0

Framework-neutral JPA/Hibernate schema migration generator. The core has **no Spring Boot, Quarkus, Hibernate, or JPA compile-time dependency**. It discovers `javax.persistence.*` or `jakarta.persistence.*` annotations reflectively from the application being inspected.

## Supported databases

- PostgreSQL
- MySQL
- MariaDB
- SQL Server
- Oracle
- H2 (development/testing)

## What the extractor understands

- `@Entity`, `@MappedSuperclass`, `@Table`, `@Column`
- `@Id`, `@Version`, `@Embedded`, `@EmbeddedId`
- `@ManyToOne`, `@OneToOne`, `@OneToMany`, `@ManyToMany`
- `@JoinColumn`, `@JoinTable`
- `@GeneratedValue(IDENTITY)`
- `@GeneratedValue(SEQUENCE)` + `@SequenceGenerator`
- inheritance: `SINGLE_TABLE`, `JOINED`, `TABLE_PER_CLASS`
- JPA table indexes
- common Java/JPA types and Hibernate `@JdbcTypeCode`/`@Nationalized` hints when present
- snake_case naming by default
- explicit `@Column(columnDefinition=...)`

Relationships are converted into foreign keys and many-to-many/unidirectional collection join tables. Database-neutral logical types are rendered by each dialect.
Collection join tables with simple single-column identifiers are supported for owning
`@ManyToMany` and `@OneToMany` mappings; inverse `mappedBy` collections do not create a second
table. Relationship mappings to composite `@Id` or `@EmbeddedId` entities are currently rejected
instead of emitting guessed join-column DDL.

### Important compatibility note

Migrax itself is framework-neutral, but your application still supplies its own JPA/Hibernate classes. This means one Migrax JAR can inspect projects using either `javax.persistence` or `jakarta.persistence` without compiling against either namespace. The CLI is built for Java 17+ in this release.

The Java packages are rooted at `io.migrax`, and the Maven plugin coordinates are
`io.migrax:migrax`. Project settings use `migrax.*` and `MIGRAX_*`; snapshots are stored in
`.migrax/snapshot.json`, and the database history tables are `migrax_history` and
`migrax_failures`.

## Build

The migration core has no runtime framework dependency. Maven plugin APIs are used only to build
the Maven plugin; H2, JUnit, and Testcontainers are test-only dependencies. With Maven installed:

```bash
mvn clean verify
```

Packaging also creates `target/migrax-0.1.0.zip`, an installable CLI distribution containing
Windows and POSIX launchers. Java 17+ is required. To use `migrax` as a command, extract the ZIP
and add its `bin` directory to `PATH`. On Windows, for example, extract it to
`C:\Tools\migrax-0.1.0`, then add `C:\Tools\migrax-0.1.0\bin` to your user `Path` in Windows
Environment Variables. Open a new PowerShell window and confirm it is available with
`Get-Command migrax`. The CLI itself does not invoke Maven. It expects application classes to be
compiled already.

## Use it

Assume your application classes are compiled into `target/classes` and your project already has its JPA API/Hibernate runtime dependencies.

### Inspect

```bash
java -cp migrax-0.1.0.jar:target/classes:<your-runtime-jars> \
  io.migrax.cli.Main inspect --package com.example.domain
```

### Preview a migration

```bash
java -cp migrax-0.1.0.jar:target/classes:<your-runtime-jars> \
  io.migrax.cli.Main plan \
  --package com.example.domain \
  --dir . \
  --dialect postgresql
```

### Generate

```bash
java -cp migrax-0.1.0.jar:target/classes:<your-runtime-jars> \
  io.migrax.cli.Main generate \
  --package com.example.domain \
  --dir . \
  --dialect postgresql \
  --name 0001_initial
```

The command writes:

```text
.migrax/snapshot.json
src/main/resources/db/migration/0001_initial.sql
```

The default migration location is `classpath:db/migration` for every project, including Spring
Boot and Quarkus; it maps to `src/main/resources/db/migration` in the Maven project. Override it
in `src/main/resources/application.properties` with `migrax.locations`, for example
`migrax.locations=filesystem:database/migrations`. A Flyway-style `spring.flyway.locations`
setting is also recognized when `migrax.locations` is not set.
Locations may use `classpath:<path>`, `filesystem:<path>`, or a plain filesystem path; relative
filesystem paths are resolved from the project root. Only one directory is currently supported.

Destructive operations require an explicit acknowledgement:

```bash
--allow-destructive
```

### Check

```bash
java -cp migrax-0.1.0.jar:target/classes:<your-runtime-jars> \
  io.migrax.cli.Main check --package com.example.domain --dir .
```

### Maven plugin workflow

Migrax's Maven plugin uses Maven to compile the service for `generate` and supplies the service
runtime classpath, including its JDBC driver. Build and install the plugin once on each developer
machine from the Migrax source checkout:

```bash
mvn clean install
```

For a team, publish the `io.migrax:migrax` plugin artifact to a Maven repository that service
builds can access instead of installing it separately on every machine.

In each service, add the plugin under `<build><plugins>` in its `pom.xml`. This is a Maven plugin
declaration, not a regular application dependency:

```xml
<build>
  <plugins>
    <plugin>
      <groupId>io.migrax</groupId>
      <artifactId>migrax</artifactId>
      <version>0.1.0</version>
      <!-- Optional when entities are outside the Maven groupId package. -->
      <!-- <configuration><basePackage>com.example.domain</basePackage></configuration> -->
    </plugin>
  </plugins>
</build>
```

Configure the service's datasource in its application configuration, then run these commands
from that service's root:

```bash
mvn migrax:generate
mvn migrax:migrate
```

`generate` compiles the service, scans the Maven groupId package for JPA entities, compares them
with `.migrax/snapshot.json`, detects the SQL dialect from the configured JDBC URL, and writes a
numbered SQL file under the configured migration location. Review the generated SQL before
applying it with `mvn migrax:migrate`. If the entity package differs from the Maven groupId, set
`<basePackage>com.example.domain</basePackage>` in the plugin configuration or pass
`-Dmigrax.package=com.example.domain`. Destructive schema changes are refused unless explicitly
enabled with `-Dmigrax.allowDestructive=true`.

Maven parses command-line options before it invokes a plugin, so plugin flags like
`--allow-destructive` cannot be passed after `mvn migrax:generate`. Use a Maven user property
instead. In PowerShell, quote Maven `-D` arguments containing dots and `=` so PowerShell passes
each property as one argument. For example:

```powershell
mvn migrax:generate "-Dmigrax.package=com.example.domain"
mvn migrax:generate "-Dmigrax.allowDestructive=true"
```

Only enable destructive generation after confirming the detected drops or alterations are
intentional. Review the generated SQL before applying it.

The Maven goal reads the datasource URL, username, and password from common Spring Boot,
Quarkus, and Hibernate keys in `src/main/resources/application.properties`,
`application.yml`, or `application.yaml`. It also accepts standard database environment
variables such as `DATABASE_URL`, `DB_URL`, `DB_USER`, and `DB_PASSWORD`. It supports
`${ENV_VAR:default}` and `${system.property:default}` placeholders in configuration values.
Recognized examples include `spring.datasource.*`, `quarkus.datasource.*`,
`datasource.*`, and JPA/Hibernate JDBC connection properties. Profile-specific Spring or
Quarkus application config files are read when the active profile is specified using
`migrax.profile`, `MIGRAX_PROFILE`, `QUARKUS_PROFILE`, or `SPRING_PROFILES_ACTIVE`.

Explicit `-Dmigrax.*` properties take precedence, followed by `MIGRAX_DATABASE_*`
environment variables, common database/framework environment variables, and application
configuration. For example, credentials can be supplied through the application config, so
the simple command needs no connection arguments:

```bash
mvn migrax:generate
```

The migration location can also be overridden with `-Dmigrax.locations=filesystem:db/sql`
or the `MIGRAX_LOCATIONS` environment variable. Generation, migration, and repair all use the
same setting. For an existing project whose SQL files are still at the old root-level
`migrations/` path, either move them to `src/main/resources/db/migration` or configure
`migrax.locations=filesystem:migrations` before running any goal.

Or override the connection with environment variables or Maven properties:

```bash
mvn migrax:generate \
  -Dmigrax.url=jdbc:mysql://localhost:3306/app \
  -Dmigrax.user=app \
  -Dmigrax.password=secret
```

Apply reviewed migrations separately:

```bash
mvn migrax:migrate
```

`migrate` applies sorted SQL files from the configured migration location and uses the service's
runtime JDBC driver. `generate` only writes SQL; it does not execute it against the database.

The standalone executable is a separate interface: extract the CLI ZIP and add its `bin` folder
to `PATH` to run `migrax generate` and `migrax migrate`. It does not need a Maven plugin
declaration, but unlike the Maven goals it does not resolve the service dependencies. See the
standalone CLI instructions below for classpath setup. You may use either interface per service;
a regular Java dependency alone provides neither Maven goals nor the `migrax` executable.

For an existing database with no snapshot, `generate` reads the current database schema through
JDBC metadata and uses it as the baseline, so it can generate changes such as adding a column
without trying to recreate existing tables. It saves the entity snapshot after generation (or
when the current entity schema already matches the database baseline). Keep migration files
and `.migrax/snapshot.json` under version control. If migration files are deleted while the
snapshot remains current, `generate` reports no changes; restore the deleted migration files from
version control.

The standalone CLI uses the same project database settings and migration location as the Maven
goals. From the application project root:

```bash
migrax generate
migrax migrate
```

If you have reviewed the generated changes and intentionally need destructive operations, pass
the CLI flag directly:

```bash
migrax generate --allow-destructive
```

`generate` takes the entity package from `MIGRAX_PACKAGE`, `--package`, or the Maven POM's
`groupId`. For non-Maven projects, set `MIGRAX_PACKAGE` or pass `--package`. `migrate` reads
the JDBC URL and credentials from the same application configuration/environment sources
described above. Both commands also accept `--url`, `--user`, `--password`, `--dir`, and
`--locations` overrides. `generate` supports `--allow-destructive` after reviewing destructive
changes; `migrate` supports the same explicit `--resume` opt-in as the Maven goal.

Migrax discovers common compiled output directories, including `target/classes` and
`build/classes/java/main`. No build tool is run for you. Add application JPA and JDBC driver JARs
to `MIGRAX_CLASSPATH` when they are not available in standard locations; separate entries with
`;` on Windows and `:` on POSIX systems. For example, on POSIX:

```bash
export MIGRAX_CLASSPATH='target/classes:lib/postgresql.jar:lib/jakarta.persistence-api.jar'
migrax generate
migrax migrate
```

You can also pass `--classpath` with the same path-list format. A configured classpath is added
to the automatically discovered build outputs and libraries. The CLI requires the project's JDBC
driver at runtime; it does not bundle database drivers.

For local development within this repository, build the project and run `bin/migrax` (or
`bin\migrax.cmd` on Windows). The same launchers are included in the extracted distribution. In
PowerShell, you can run the Windows launcher directly without changing `PATH`:

```powershell
& 'C:\Tools\migrax-0.1.0\bin\migrax.cmd' generate
```

The runner creates `migrax_history`, stores a SHA-256 checksum for every applied migration,
skips already-applied migrations, and fails if an applied migration file changes or disappears.
Failed and interrupted runs are tracked in `migrax_failures`; ordinary `migrate` calls stop
when an incomplete attempt exists.
It serializes concurrent migration runs using database advisory locks on MySQL/MariaDB,
PostgreSQL, SQL Server, and Oracle (Oracle requires permission to execute `DBMS_LOCK`). H2
locking is process-local and intended only for development/tests.

### Recovery after a failed migration

The default is fail-stop. Inspect the database and back it up before deciding on recovery.

- If every statement did take effect and you have verified the resulting schema/data, mark the
  migration applied explicitly:

  ```bash
  mvn migrax:repair \
    -Dmigrax.version=0002_add_column.sql \
    -Dmigrax.action=applied \
    -Dmigrax.confirm=true
  ```

- If you manually restored the database to its pre-migration state, clear the failed marker so
  the original SQL can be run again:

  ```bash
  mvn migrax:repair \
    -Dmigrax.version=0002_add_column.sql \
    -Dmigrax.action=retry \
    -Dmigrax.confirm=true
  ```

- An optional automatic resume is allowed only when the SQL file contains a standalone
  `-- migrax:resume-safe` line and every statement in that file is safe to replay. After
  explicitly reviewing that condition and the database state, run:

  ```bash
  mvn migrax:migrate -Dmigrax.resume=true
  ```

  This flag cannot make non-idempotent SQL safe; use it only for deliberately replay-safe
  migrations. Do not use it as a substitute for investigating an interrupted DDL migration.

Repair changes only Migrax's history after operator confirmation; it does not inspect or
repair schema objects automatically. Keep an audit record of every repair.

### Database integration tests

`DatabaseEngineIT` is under the standard `src/test/java` source root so IDEs and Maven recognize
it as a test. Its Testcontainers and JDBC driver dependencies are test-scoped and do not enter
Migrax's runtime/plugin dependencies. The normal Surefire test run excludes `*IT` tests; the
`database-integration` profile enables Failsafe to run this test against disposable containers
for MySQL, MariaDB, PostgreSQL, SQL Server, and Oracle XE. Run it only where Maven/Testcontainers
can access Docker:

```bash
mvn verify -Pdatabase-integration
```

The regular `mvn verify` suite uses H2 for fast runner tests. A skipped or unavailable
integration profile is not evidence that the production database engine/version has passed.
Run the matrix in CI and retain the report before claiming engine support.

## Operational limitations

Treat generated SQL as a reviewed artifact. Keep all applied SQL files and the entity snapshot
in version control; create a new forward migration to undo a change rather than editing an
applied file. Take and verify database backups before applying production migrations.

Do not generate migrations against production as a substitute for release review. Generate from a
development/staging schema, review the SQL and its impact, test it against a production-like copy,
and deploy the reviewed migration as a controlled release. Before production, verify a restorable
backup, estimate table size and lock/rebuild behavior for the exact database engine/version, and
schedule changes that may block application traffic. Migrax's advisory/application lock only
serializes cooperating Migrax runs; it does not prevent application queries from being blocked
by database DDL locks.

The database lock is fail-fast: when another Migrax process holds it, the concurrent run exits
with an error rather than waiting. It coordinates only Migrax processes using the same lock
convention; it does not coordinate with application ORM schema updates, DBA scripts, or other
migration products. Disable automatic ORM schema mutation in production and coordinate external
schema changes separately.

Supported lock implementations are MySQL/MariaDB `GET_LOCK`, PostgreSQL advisory locks, SQL
Server application locks, and Oracle `DBMS_LOCK` (which requires execute permission). H2 locking
is process-local and is not a production concurrency mechanism. The Testcontainers integration
profile checks competing connections and migration behavior against its configured engine images;
run and retain results for the exact production engine/version and driver before rollout.

The runner executes statements as provided; it does not guarantee transactional DDL. MySQL,
Oracle, and other databases may commit DDL implicitly. A failed attempt is marked incomplete,
and default retries stop until an operator repairs history or explicitly opts into replay-safe
resume. SQL parsing handles quoted strings/identifiers, comments, and PostgreSQL dollar-quoted
bodies, but not client-specific `DELIMITER` directives or SQL Server `GO` batch separators.

Although dialects are implemented for several databases, production readiness requires passing
the integration profile against the exact database version and JDBC driver used by each
service. Oracle locking additionally depends on `DBMS_LOCK` privileges. Do not treat H2 tests
as a substitute for integration tests against the production database engine, and do not run
schema generation against production as a substitute for reviewing migrations in CI/staging.

If an applied migration file was deleted, restore the exact SQL from the service's Git history
or a trusted backup and verify its SHA-256 matches the database history record. Do not recreate
or edit the file from memory and do not delete rows from `migrax_history` to bypass the check.
For example, inspect a known commit with
`git show <commit>:src/main/resources/db/migration/<filename>.sql` and
restore that original file before running `migrate`.

## Dialects

Generate with `--dialect` values:

```text
postgresql
mysql
mariadb
sqlserver
oracle
h2
```

`migrate` detects the dialect from the JDBC URL where needed by the application layer. H2 is intended primarily for local development and tests.

## Typical workflow

1. Compile the application.
2. Run `inspect` and verify the extracted schema.
3. Run `plan` and review the operations.
4. Run `generate` to create a migration and update the snapshot.
5. Test the migration against a disposable database.
6. Commit the migration and `.migrax/snapshot.json`.
7. Run `migrate` in the deployment environment.
8. Run `check` in CI to ensure entities and snapshot agree.
