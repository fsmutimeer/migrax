# Rollbacks and recovery

## Rolling back

Every generated migration has a rollback script in `rollback/<same name>.sql`. To undo the newest
migration:

```console
$ migrax rollback --yes
Rolling back 1 migration(s), newest first:
  0006_create_categories_seq_and_more.sql
Rolled back migration '0006_create_categories_seq_and_more.sql'.
```

| Option | Effect |
|---|---|
| `--steps 3` | Roll back the three newest migrations |
| `--to 0004_add_index.sql` | Roll back everything after `0004_add_index.sql` |
| `--dry-run` | List what would be rolled back |
| `--yes` | Required: confirms the change. Back up first. |

The rolled-back migrations become pending again, so `migrax migrate` re-applies them.

!!! warning "Structure, not data"

    A rollback restores the **structure**: re-creating a dropped column brings the column back,
    not its values. The header of each rollback script says what cannot be restored:

    ```sql
    -- Rollback for 0006_create_categories_seq_and_more.sql. 'migrax rollback' runs it.
    -- Note: deletes data written to categories since the migration ran.
    ```

Hand-written migrations from `migrax new` get an empty rollback script for you to fill in. Java
migrations roll back through `JavaMigration.rollback(Connection)`.

`migrax verify` proves the rollback scripts work: it rolls every migration back on a
throwaway database and applies them again ([Production safety](production-safety.md)).

## When a migration fails

A failed migration is recorded in `migrax_failures`, and `migrate` stops until you decide what
happened:

```console
$ migrax status
  [X] 0005_fix_naming.sql                      applied 2026-10-08 11:04:45
  [!] 0006_create_categories_seq_and_more.sql  FAILED: SQLSyntaxErrorException: Table 'categories_seq' already exists
6 applied, 1 failed.
Some migrations need attention. See 'migrax help repair'.
```

The message is the database's own error. Look at the database, then tell Migrax which case
you are in:

=== "The statements took effect"

    Every statement in the migration is in the database (for example, you finished it by hand):

    ```console
    $ migrax repair 0006_create_categories_seq_and_more.sql --action applied --yes
    ```

=== "You restored the database"

    You undid the partial changes, or nothing was applied; run it again:

    ```console
    $ migrax repair 0006_create_categories_seq_and_more.sql --action retry --yes
    $ migrax migrate
    ```

Keep a record of every repair. Never edit an applied migration or delete history rows by hand.

### Migrations that are safe to re-run

A migration that starts with `-- migrax:resume-safe` can be re-run after a failure with
`migrax migrate --resume`, without a repair. Only mark migrations whose statements can run
twice (for example `CREATE TABLE IF NOT EXISTS`, idempotent updates).

## A migration file went missing

`status` reports applied migrations whose file is gone, and `migrate` stops:

```console
$ migrax migrate
error: Applied migration file(s) are missing from the configured migration location: 0001_initial.sql. Restore the files from version control; applied migrations must not be deleted. If you deleted them on purpose, remove them from the history with: migrax repair 0001_initial.sql --action forget --yes
```

**Deleted by accident?** Restore the file from git (`git show <commit>:<path>`), unchanged. This
is the right fix in almost every project: other environments and new databases still need it.

**Deleted on purpose**, for example to start a test database over? Remove the migrations from
the history:

```console
$ migrax repair 0001_initial.sql 0002_add_email.sql --action forget --yes
Removed 0001_initial.sql from the migration history.
Removed 0002_add_email.sql from the migration history.
The database keeps the changes these migrations made. Run 'migrax status' to check the history.
```

The tables and columns those migrations created stay in the database; only the record goes.
Migrax refuses to forget a migration whose file still exists, because `migrate` would run it
again.

!!! tip "Starting over with the current database as the baseline"

    To make the current database the starting point, forget the deleted migrations, delete
    `.migrax/snapshot.json` and the `.migrax/history` folder, and run `migrax generate`: with no
    snapshot, it compares the entities with the database. It writes the current tables to
    `0001_baseline.sql` (recorded as applied, not run) and what is really different to a second
    migration. Review that migration before running `migrax migrate`.

    From then on the migrations start at the current database. On other databases that still
    have the old history, the baseline would try to create tables that exist, so for shared
    environments restore the deleted files instead.
