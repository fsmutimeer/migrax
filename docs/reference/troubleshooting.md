# Troubleshooting

Start with `migrax doctor`: it checks Java, the build, the entities, the database connection
and risky settings, and says how to fix each problem. Add `--verbose` to any command for debug
output and stack traces.

??? question "`migrax` is not recognized"

    The terminal was opened before Migrax was installed. Open a new terminal; for the terminal
    inside VS Code or IntelliJ IDEA, restart the editor. See
    [Installation](../getting-started/installation.md#3-check-it).

??? question "No database URL found"

    Migrax looks for the URL in your framework's usual setting; the error names the right key for
    your framework. Set it there, or use `MIGRAX_DATABASE_URL` / `--url`. See
    [Configuration](configuration.md).

??? question "Hibernate could not read the entity mapping; scanning JPA annotations instead"

    Migrax fell back to annotation scanning. Run with `--extractor hibernate` to see the exact
    error. Common causes: entities using `javax.persistence` with Hibernate 6+ (or `jakarta` with
    Hibernate 5), or a Hibernate version older than 5.4.

??? question "A migration fails with \"already exists\""

    Something created the table before the migration ran, usually Hibernate with
    `ddl-auto=update` or `schema-management.strategy=update`. Set it to `none`, restore the
    database to the state before the migration, then
    `migrax repair <migration> --action retry --yes` and `migrax migrate`.

??? question "Generated SQL renames or drops every column"

    The naming strategy doesn't match the one your tables were created with. Check the
    `Naming:` line of `migrax doctor`, and set `migrax.naming` to the strategy the tables use.
    See [Naming strategies](naming.md).

??? question "`migrate` stops: a migration changed or is missing"

    Applied migrations must not change. Restore the original file from git, and put the change in
    a new migration (`migrax new`).

??? question "`--no-build` cannot find the JDBC driver"

    `--no-build` reuses the dependencies resolved by the last build. Run one command without it
    first (or with `--refresh`).

??? question "`verify` cannot start a database"

    For engines other than H2, `verify` needs Docker. Without Docker, pass an empty scratch
    database: `migrax verify --url <jdbc-url>`.
