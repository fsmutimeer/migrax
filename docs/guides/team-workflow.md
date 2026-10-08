# Working in a team

## What to commit

| Commit | Ignore (Migrax does it for you) |
|---|---|
| `src/main/resources/db/migration/*.sql` | `.migrax/classpath.txt`, `.migrax/classpath.hash` |
| `src/main/resources/db/migration/rollback/` | `.migrax/build.stamp` and other caches |
| `.migrax/snapshot.json` | |
| `.migrax/history/` | |

The snapshot is written with one table, column, index and key per line, so git can merge changes
two people made to different tables.

## Two branches, same migration number

Alice and Bob both branch from `0007`. Each generates a migration: both get `0008`. After the
merge, `generate` refuses to continue and `check` fails:

```console
$ migrax check
Migrations share numbers: [[0008_add_orders_notes.sql, 0008_create_invoices.sql]].
Run 'migrax merge'.
```

```console
$ migrax merge
Renamed 0008_create_invoices.sql to 0009_create_invoices.sql.
Next: run 'migrax check' (no changes expected) and 'migrax verify' to prove the merged
migrations produce the entity schema.
```

The migration added later moves to the next free number, together with its rollback script.
`merge` also rebuilds a snapshot that has git conflict markers.

Migrax never renames a migration that the configured database has already applied. If both
files with the same number are already applied, they are settled history: they run in file
name order and are left as they are.

## Too many migration files?

After a while, squash old migrations into one:

```console
$ migrax squash --to 0040_add_audit_columns.sql
```

The squashed file replaces `0001` to `0040`. Databases that already applied those record the
squashed file without running it; new databases run only the squashed file. `--optimize`
writes just the resulting schema and drops data statements. Delete the old files once every
environment has run `migrate`.

## Reviewing migrations

Treat a migration like code:

- read the SQL in the pull request (the [GitHub Action](ci.md) posts it as a comment);
- check the lint findings;
- let CI run `migrax check` and `migrax verify`.
