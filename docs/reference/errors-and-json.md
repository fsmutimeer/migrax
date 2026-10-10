# Errors and JSON output

Every command can report its result as JSON with `--json`, and every error has a stable code.
Scripts, CI jobs and AI assistants can act on both without reading the text.

## JSON output

With `--json`, a command prints exactly one JSON value on standard output. Progress messages
("Applying migration ...") go to standard error, so standard output stays parseable.

These commands print a result object with `ok` (true when the exit code is 0), `exitCode` and
the command's own fields:

| Command | Fields |
|---|---|
| `migrate` | `schemas`: one entry per schema with `schema`, `applied`, `total`; with `--dry-run`, `wouldApply` (the pending migrations) |
| `rollback` | `schemas`: `schema`, `rolledBack` (or `wouldRollBack` with `--dry-run`) |
| `repair` | `action`; `repaired` or `forgotten` (the migrations) |
| `clean` | `schemas`: `schema`, `dropped` (or `wouldDrop` with `--dry-run`) with `tables`, `views`, `sequences` |
| `doctor` | `checks` (each with `status`: `ok`, `info`, `warn` or `fail`, `message`, `fix`), `failures`, `warnings` |
| `new`, `squash` | `created`; `squash` also `replaces` |
| `merge` | `renamed` (each with `from`, `to`), `snapshotRebuilt` |

```console
$ migrax migrate --json 2>/dev/null
{"ok":true,"exitCode":0,"schemas":[{"schema":null,"applied":1,"total":1}]}
```

`status`, `plan`, `check`, `drift`, `lint`, `verify` and `init` keep the JSON shapes they had
before 0.3.0; see their sections in the [CLI reference](cli.md).

### When a command fails

With `--json`, a failure is also one JSON object on standard output, with the exit code 1:

```json
{"ok":false,"exitCode":1,"error":{"code":"MXE104","message":"Checksum changed for applied migration 0001_initial.sql","hint":"..."}}
```

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Success |
| 1 | Error (see the error code) |
| 2 | Changes or drift found (`check`, `drift`) |

## Error codes

Errors print as `error[MXE104]: <message>` followed by a hint. A code never changes its
meaning; new situations get new codes.

| Code | Meaning | What to do |
|---|---|---|
| MXE000 | Unexpected error | Run again with `--verbose` and report it with the output |
| MXE001 | Wrong command, option or input | Read the hint; `migrax help <command>` lists the options |
| MXE101 | Another process holds the migration lock | Wait, or use `--lock-timeout` so instances wait for each other |
| MXE102 | A migration failed | Fix the migration; on databases without transactional DDL, check what was applied, then `repair` |
| MXE103 | A failed migration must be repaired first | Inspect the database, then `migrax repair <migration> --action applied` (or `retry`) |
| MXE104 | An applied migration file was changed | Restore the file; write a new migration for new changes |
| MXE105 | An applied migration file is missing | Restore it, or `migrax repair <migration> --action forget --yes` if you deleted it on purpose |
| MXE106 | No JDBC driver for the database URL | Add the driver to the project, `--classpath`, or the drivers folder |
| MXE107 | Cannot connect to the database | Check the URL, network, user and password |
| MXE108 | Destructive changes need confirmation | Review them; `--allow-destructive`, or `--rename` for renames |
| MXE109 | Migrations share numbers | `migrax merge` renumbers them |
| MXE110 | No entities found | Pass `--package`; `migrax doctor` shows what Migrax found |
| MXE111 | Docker is needed for verify | Start Docker, or pass `--url` with an empty scratch database |
| MXE112 | clean is disabled | `MIGRAX_CLEAN_DISABLED` is set; clean only development databases |
| MXE113 | No database URL configured | Set it in the application config, `MIGRAX_DATABASE_URL`, or `--url` |
