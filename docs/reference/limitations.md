# Limitations

What Migrax doesn't do yet, and what to do instead.

## Reading your entities

**Hibernate older than 4.2.** Migrax reads these projects from their annotations, using the
rules of current Hibernate versions. Most mappings come out the same, but older versions differ
in places: they share one `hibernate_sequence` between entities, for example.
`migrax doctor` tells you when this applies. Review the generated migrations and run
`migrax verify` before you release them. See [JPA providers](hibernate.md).

**JPA providers other than Hibernate, EclipseLink and OpenJPA**, such as DataNucleus, are read
the same way, with Hibernate's naming rules. If your provider names tables or columns differently, set
the names in your entities (`@Table`, `@Column`) or check the generated migrations.

## Rolling back

**A rollback brings back the structure, not the data**, unless the migration was generated with
`--keep-data`. Rolling back a migration that dropped a column re-creates the column, but its
values are gone; with `--keep-data` they come back from a backup table. See
[Keeping dropped data](../guides/rollbacks-and-recovery.md#keeping-dropped-data).

## Getting Migrax

**Migrax isn't on Maven Central yet.** To use the Maven plugin or a framework integration,
install the libraries from source, as described on each framework's page. The command line tool
and the [container image](../guides/containers-and-kubernetes.md) don't need this.
