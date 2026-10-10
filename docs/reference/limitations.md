# Limitations

Known limits in Migrax 0.2.0:

- **Check constraints and comments** (`@Check`, `@Comment`, enum check constraints) are not
  generated. Add them with a hand-written migration (`migrax new`).
- **One migration folder** per project.
- **Statement splitting:** SQL Server `GO` batches and client `DELIMITER` commands are not
  supported; PL/SQL blocks with inner semicolons must be split by hand.
- **Hibernate before 5.4** is read by annotation scanning with Hibernate 6's rules. See
  [Hibernate versions](hibernate.md).
- **Other JPA providers** are read by annotation scanning with Hibernate's naming rules.
- **Quarkus:** the startup integration runs in JVM mode, after Hibernate has started. Keep
  `quarkus.hibernate-orm.schema-management.strategy=none`.
- **Rollback scripts** restore structure, not deleted data.
- **Application servers:** data sources defined in the server's configuration are not read; set
  the connection with environment variables or options.
- **SQLite:** Hibernate can't load `@Lob` fields through the SQLite driver, and `verify` uses
  Migrax's structural check instead of Hibernate's validation. See
  [Databases](databases.md#sqlite).
- **CockroachDB:** some column type changes need CockroachDB's experimental settings; review
  migrations that change types. See [Databases](databases.md#cockroachdb).
- **Distribution:** Migrax is not on Maven Central yet. Install the libraries from source, or
  use the [container image](../guides/containers-and-kubernetes.md).
