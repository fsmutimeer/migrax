# Using Migrax with AI assistants

AI coding assistants can use Migrax directly: they read what changed, preview the SQL with its
risks, and explain it to you, while you stay the one who changes the database.

## The MCP server

`migrax mcp` is a [Model Context Protocol](https://modelcontextprotocol.io) server. Assistants
that support MCP, such as Claude Code, start it and call Migrax's commands as tools, getting
their [JSON results](../reference/errors-and-json.md).

| Tool | What it does |
|---|---|
| `status` | Which migrations are applied, pending, failed or changed |
| `check` | Whether entities changed without a migration |
| `plan` | The SQL `generate` would write, with lint findings and table sizes |
| `lint` | Locking and risky statements in migrations |
| `drift` | Manual changes in the database |
| `doctor` | Setup problems and how to fix them |
| `verify` | Migrations applied to a throwaway database and validated (slow) |
| `show_migration` | One migration file's SQL, or its rollback script |
| `generate` | Writes a migration; only with `migrax mcp --allow-generate` |

The server never changes a database: `migrate`, `rollback`, `repair` and `clean` are not
offered, so an assistant can't apply or undo migrations on its own. With `--allow-generate` it
can write migration files, which you review in version control before running
`migrax migrate` yourself.

### Claude Code

Run this in your service folder:

```console
$ claude mcp add migrax -- migrax mcp
```

On Windows, `migrax` is a `.cmd` script, so start it through `cmd`:

```console
> claude mcp add migrax -- cmd /c migrax mcp
```

To share it with your team, add `--scope project`: Claude Code then writes the setting to
`.mcp.json` in the project, which you commit. To let the assistant write migrations, end the
command with `migrax mcp --allow-generate`.

### Other MCP clients

Clients configured with JSON take the same command:

```json
{
  "mcpServers": {
    "migrax": {
      "command": "migrax",
      "args": ["mcp"]
    }
  }
}
```

The server runs in the folder the client starts it in; pass `"args": ["mcp", "--dir",
"/path/to/service"]` to point it elsewhere. It uses the same database settings as the command
line (application config, `MIGRAX_DATABASE_URL`, ...).

## Instructions for your project's assistant

Assistants follow the instructions in files such as `AGENTS.md` or `CLAUDE.md`. This block
tells them how to work with Migrax; copy it into yours:

```markdown
## Database migrations (Migrax)

- The schema is managed by Migrax. Never change the database by hand or with Hibernate's
  schema generation (`ddl-auto`, `schema-management.strategy`): change the entities, then run
  `migrax plan` to preview and `migrax generate` to write the migration.
- Read the plan's lint findings before generating. Explain locking statements, NOT NULL
  columns on existing tables and anything marked [DESTRUCTIVE].
- A renamed field or table must be passed as a rename (`--rename table.old=new`,
  `--rename-table old=new`); otherwise its data is dropped. Ask when unsure.
- Never edit a migration that is already applied anywhere; write a new one.
- Do not run `migrax migrate`, `rollback`, `repair` or `clean` against shared or production
  databases. Tell the person what to run.
- Use `--json` for machine-readable output; errors have stable codes (MXE...).
```

## Documentation for assistants

The documentation is also published as plain text for AI assistants:

- [llms.txt](https://docs-migrax.github.io/llms.txt): an index of every page, with a one-line
  summary each;
- [llms-full.txt](https://docs-migrax.github.io/llms-full.txt): every page in one file.

Point an assistant at `llms-full.txt` when it needs to answer questions about Migrax.
