# Limitations

Known limits in Migrax 0.1.1:

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
- **Distribution:** 0.1.1 is not on Maven Central yet. Install the libraries from source.
