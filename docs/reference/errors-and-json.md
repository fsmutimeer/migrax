---
seo_title: "Error codes and JSON output"
description: "Stable error codes MXE000 and up, and the JSON output of every command, for scripts, CI and tools."
---
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
| `generate` | `noChanges`, `operations` (as in `check --json`, plus `destructive`), `renames`, `created`, `waiting`, `notKept`, `dataNotKept`; see below |

```console
$ migrax migrate --json 2>/dev/null
{"ok":true,"exitCode":0,"schemas":[{"schema":null,"applied":1,"total":1}]}
```

`status`, `plan`, `check`, `drift`, `lint`, `verify` and `init` keep the JSON shapes they had
before 0.3.0, apart from the fields added below; see their sections in the
[CLI reference](cli.md).

Each migration in `status --json` has its `version` (the file name of a SQL migration),
`state` (`applied`, `pending`, `outdated`, `skipped`, `failed`, `changed` or `missing`),
`detail` and `file`: the
path of the migration file relative to the project folder, such as
`src/main/resources/db/migration/0002_add_email.sql`, so an editor opens the right file even
when two modules have one with the same name. `file` is null for Java migrations and for a
`missing` migration, whose file is gone. A folder outside the project folder gives an
absolute path as the system writes it (with `\` on Windows).

Each change in `plan --json` (`operations`) and `check --json` (`operations`, next to the
plain-text `changes`) names its `kind`, `table`, `column` (null when the change is not about one
column) and `entity`: the class of the entity that maps the table, such as
`com.example.Customer`. Editors and other tools use it to point at the class. `entity` is null
for a dropped table, for tables no entity class maps directly (join, collection and secondary
tables), for a schema file, and when the entities are read with Hibernate 4, EclipseLink,
OpenJPA, Spring Data JDBC or Micronaut Data. A single-table hierarchy names its root class.

```console
$ migrax check --json    # one line, wrapped here
{"ok":false,"changes":["add column orders.notes"],"operations":[{"kind":"add_column",
"summary":"add column orders.notes","table":"orders","column":"notes",
"entity":"com.example.Order"}],"conflicts":[]}
```

### generate --json

`generate --json` never asks questions. Each possible rename it would have asked about comes
back in `renames` with `what` (as in the question), `option` (`--rename` or `--rename-table`)
and `value`; run `generate` again with that option and value to keep the data. `created` lists
the files written, as the text output names them, relative to the project folder when they are
inside it (a first baseline, the migration, the
`--safe` online migration, the expand step, or with `--contract` the released contract
migrations). `waiting` lists the contract migrations that `--zero-downtime` keeps in
`.migrax/pending/` in this run; earlier ones still waiting are only mentioned in the text.
`notKept` names the columns (`table.column`) whose data `--keep-data` can't keep.
`dataNotKept` names the dropped or narrowed tables and columns of a migration written without
`--keep-data`, whose rollback brings back the structure but not the data. It is a report:
the migration is already written. To let the user choose, ask when `generate` stops at drops
(see below) and pass `--keep-data` with `--allow-destructive`. `noChanges`
is true when the entities match the snapshot (or, on the first run, the database), and with
`--contract` when nothing waits; `created` can still list the baseline of a first run.

When `generate` fails after it has read the changes, the error object still has these fields.
For example, when it stops because of drops (`MXE108`), `operations` lists them with
`destructive` true, next to the `renames`, so a tool can ask about both and run it again with
`--allow-destructive` or the rename options. A failure before that (no database URL, no
entities) has only `ok`, `exitCode` and `error`.

```console
$ migrax generate --json 2>/dev/null    # one line, wrapped here
{"ok":true,"exitCode":0,"noChanges":false,"operations":[{"kind":"add_column",
"summary":"add column orders.notes","table":"orders","column":"notes",
"entity":"com.example.Order","destructive":false}],"renames":[],
"created":["src/main/resources/db/migration/0004_add_orders_notes.sql"],"waiting":[],
"notKept":[],"dataNotKept":[]}
```

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
| MXE114 | A migration's precondition is not met | Change the database so the `-- migrax:require` line holds, or end the line with `else skip` |
| MXE115 | The schema file can't be read | Fix the statement the message names, start Docker, or pass `--scratch-url` with an empty database |
| MXE116 | No environments configured | Give each environment a URL: `migrax.environments.<name>.url` or `MIGRAX_ENVIRONMENTS_<NAME>_URL` |
| MXE117 | A migration waited too long for a locked table | Nothing was changed: run `migrate` (or `rollback`) again when the table is less busy, or raise `--table-lock-timeout` ([details](configuration.md#table-lock-timeout)) |
