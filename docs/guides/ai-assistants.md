---
seo_title: "Database migrations with AI coding assistants (MCP)"
description: "Let AI coding assistants inspect entity changes and preview migrations through the Migrax MCP server, in Claude Code, Codex, Gemini CLI, Copilot and Cursor."
---
# Using Migrax with AI assistants

AI coding assistants can use Migrax directly: they read what changed, preview the SQL with its
risks, and explain it to you, while you stay the one who changes the database.

## The MCP server

`migrax mcp` is a [Model Context Protocol](https://modelcontextprotocol.io) server. Assistants
that support MCP, such as Claude Code, Codex CLI, Gemini CLI, GitHub Copilot in VS Code and
Cursor, start it and call Migrax's commands as tools, getting their
[JSON results](../reference/errors-and-json.md).

| Tool | What it does |
|---|---|
| `status` | Which migrations are applied, pending, failed or changed |
| `check` | Whether entities changed without a migration |
| `plan` | The SQL `generate` would write, with lint findings and table sizes |
| `lint` | Locking and risky statements in migrations |
| `drift` | Manual changes in the database |
| `environments` | Every environment's applied and pending migrations and drift |
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

### Codex CLI

Run this in your service folder:

```console
$ codex mcp add migrax -- migrax mcp
```

Codex writes the server to `~/.codex/config.toml`, so it is available in every folder you start
Codex in. To set it up for one project only, put the same lines in `.codex/config.toml` in the
project (Codex reads it in projects you trust):

```toml
[mcp_servers.migrax]
command = "migrax"
args = ["mcp"]
```

Codex finds `migrax.cmd` on Windows by itself, so the command is the same there.

### Gemini CLI

Run this in your service folder:

```console
$ gemini mcp add migrax migrax mcp
```

Gemini writes the server to `.gemini/settings.json` in the project; add `--scope user` to
use it in every folder. `gemini mcp list` shows whether it connects. Gemini starts MCP servers
only in folders you trust, so trust the folder when it asks. Like Codex, it finds `migrax.cmd`
on Windows by itself.

### GitHub Copilot in VS Code

Create `.vscode/mcp.json` in your project:

```json
{
  "servers": {
    "migrax": {
      "type": "stdio",
      "command": "migrax",
      "args": ["mcp"]
    }
  }
}
```

VS Code starts the server in the workspace folder. Note that the top-level key is `servers`
here, not `mcpServers` as in the other clients.

### Cursor

Create `.cursor/mcp.json` in your project, or `~/.cursor/mcp.json` to use Migrax in every
project:

```json
{
  "mcpServers": {
    "migrax": {
      "type": "stdio",
      "command": "migrax",
      "args": ["mcp", "--dir", "${workspaceFolder}"]
    }
  }
}
```

`${workspaceFolder}` is the folder you opened in Cursor, so the server always reads that
service.

### Other MCP clients

Most other clients (Claude Desktop, Windsurf, Cline, ...) read the same JSON as Cursor:

```json
{
  "mcpServers": {
    "migrax": {
      "command": "migrax",
      "args": ["mcp", "--dir", "/path/to/service"]
    }
  }
}
```

The server runs in the folder the client starts it in; `--dir` points it at your service
instead. It uses the same database settings as the command line (application config,
`MIGRAX_DATABASE_URL`, ...).

### Windows: start Migrax through `cmd`

On Windows, `migrax` is a `.cmd` script. Some clients start programs directly and can't run
scripts, so they report that `migrax` was not found even though it works in a terminal. In
JSON files, start it through `cmd` instead:

```json
"command": "cmd",
"args": ["/c", "migrax", "mcp"]
```

This works in every client. Codex CLI and Gemini CLI don't need it.

### ChatGPT

The ChatGPT app connects only to MCP servers on the internet, and `migrax mcp` runs on your
own machine next to your code and database. To use Migrax with OpenAI's models, use Codex CLI
(above).

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

- [llms.txt](https://migrax.org/llms.txt): an index of every page, with a one-line
  summary each;
- [llms-full.txt](https://migrax.org/llms-full.txt): every page in one file.

Point an assistant at `llms-full.txt` when it needs to answer questions about Migrax.
