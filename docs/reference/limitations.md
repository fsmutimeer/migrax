# Limitations

What Migrax doesn't do yet, and what to do instead.

## Reading your entities

**Hibernate older than 5.0.** Migrax reads these projects from their annotations, using the
rules of current Hibernate versions. Most mappings come out the same, but older versions differ
in places: Hibernate 4 shares one `hibernate_sequence` between entities, for example.
`migrax doctor` tells you when this applies. Review the generated migrations and run
`migrax verify` before you release them. See [Hibernate versions](hibernate.md).

**JPA providers other than Hibernate, EclipseLink and OpenJPA**, such as DataNucleus, are read
the same way, with Hibernate's naming rules. If your provider names tables or columns differently, set
the names in your entities (`@Table`, `@Column`) or check the generated migrations.

## Running migrations

**`--schemas` on SQL Server.** SQL Server can't change a connection's default schema, so Migrax
can't move from one schema to the next. Run Migrax once per schema, each time with a login
whose default schema is that schema.

**Oracle package bodies and anonymous PL/SQL blocks** need a line with only `/` after them.
Procedures, functions and triggers don't; see
[Procedures, functions and triggers](../guides/advanced-migrations.md#procedures-functions-and-triggers).

## Rolling back

**A rollback brings back the structure, not the data**, unless the migration was generated with
`--keep-data`. Rolling back a migration that dropped a column re-creates the column, but its
values are gone; with `--keep-data` they come back from a backup table. Data in a table without a
primary key can only be kept when the whole table is dropped. See
[Keeping dropped data](../guides/rollbacks-and-recovery.md#keeping-dropped-data).

## Getting Migrax

**Migrax isn't on Maven Central yet.** To use the Maven plugin or a framework integration,
install the libraries from source, as described on each framework's page. The command line tool
and the [container image](../guides/containers-and-kubernetes.md) don't need this.
