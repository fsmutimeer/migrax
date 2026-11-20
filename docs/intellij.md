---
seo_title: "IntelliJ IDEA plugin for database migrations"
description: "The Migrax plugin for IntelliJ IDEA and the other JetBrains IDEs: generate, review, apply and roll back database migrations without leaving the editor. Free, for IntelliJ IDEA, PyCharm, WebStorm, GoLand, Rider and more."
---
# IntelliJ IDEA plugin

The Migrax plugin brings Migrax into IntelliJ IDEA: a tool window with the state of every
migration, buttons for the everyday commands, an icon next to entity classes whose changes are
not in a migration yet, and lint findings marked inside migration files. It also runs in
PyCharm, WebStorm, GoLand, Rider and the other JetBrains IDEs, for projects with
[hand-written SQL migrations](frameworks/any-language.md).

The plugin has no migration logic of its own. Every button runs the Migrax command line in the
background and shows its answer, so the IDE gives exactly the result your terminal and your CI
give.

The plugin is free on the
[JetBrains Marketplace](https://plugins.jetbrains.com/plugin/35665-migrax), and its source code
is on [GitHub](https://github.com/fsmutimeer/migrax-intellij).

## Requirements

| What | Needed |
|---|---|
| IDE | IntelliJ IDEA 2024.2 or newer (Community or Ultimate), or another JetBrains IDE from 2024.2 on: PyCharm, WebStorm, GoLand, Rider and the others |
| Migrax | 0.17 or newer. Without one, the plugin offers to download it ([below](#the-migrax-command)) |
| Java | 17 or newer, for the Migrax command |
| Project | Migrations in SQL files. For **Generate**, **Plan**, **Drift** and **Verify** also JPA/Hibernate entities and a `pom.xml` or `build.gradle`, as for the [command line](getting-started/quickstart.md) |

Some buttons need more:

- **Migrate, Roll Back, Repair, Drift, Backups** and the Status tab need the database in your
  settings to be reachable.
- **Verify** starts a throwaway database: in memory for H2, HSQLDB and Derby, in Docker for the
  others.
- **Kotlin entities** need the Kotlin plugin, which comes with IntelliJ IDEA.
- **Roll Back's** list of what it brings back without data needs Migrax 0.19 or newer, and
  `migrax.properties` in the project folder needs Migrax 0.20 or newer.

## Install

1. In IntelliJ IDEA open **Settings > Plugins > Marketplace**, search for **Migrax** and click
   **Install**.
2. Open your service's project. The plugin finds Migrax projects by their `.migrax` folder,
   `migrax.properties` or `migrax.*` settings, also in a sub-folder up to three levels deep, or
   by a database set for the project ([below](#the-projects-database)).
3. In a project that does not use Migrax yet, click **Set up Migrax** in the Migrax tool window
   (or **Tools > Migrax > Set Up Migrax...**) and pick the service's folder. It runs
   [`migrax init`](reference/cli.md#init) there and shows what Migrax detected.

### The Migrax command

The plugin looks for the Migrax command in this order:

1. The path in **Settings > Tools > Migrax**, if you set one.
2. The `migrax` command on your `PATH`, as installed from the [Download page](download.md),
   Homebrew or Scoop.
3. The copy the plugin downloaded itself.

When it finds none, it shows a notification with **Download Migrax**. The plugin then downloads
the newest Migrax release (about 1 MB) into the IDE's own folder:

- It reads the newest version and its SHA-256 from the Migrax Scoop package, which every
  release writes, and downloads only that version's zip from the Migrax releases on GitHub.
- It checks the zip against the SHA-256 before unpacking it. A file that does not match is not
  installed.
- Offline, or behind a proxy that blocks GitHub, it uses the release the plugin was built with.
  The download uses the IDE's proxy settings (**Settings > Appearance & Behavior > System
  Settings > HTTP Proxy**).
- When a newer release is out, it offers the update once per IDE session.

An installed Migrax is always used first and is never changed by the plugin.

When the path in **Settings > Tools > Migrax** no longer exists (a moved or deleted folder), a
notification offers **Use the Installed Migrax** when it finds another Migrax on your computer,
or else **Clear the Setting**, after which the plugin offers the download. It never switches by itself,
since another Migrax version could write different migrations.

### The project's database

The plugin's commands use the database in the project's own settings, as in a terminal. For a
project whose settings Migrax can't read from a file (such as a Python project that gets its
database from environment variables in a terminal, which the IDE doesn't see), or to use another
database in the IDE, set it in **Settings > Tools > Migrax > This Project**: the JDBC URL, the
user, the password and the migrations folder.

- They are kept for you only: in the IDE's workspace file, which is normally not committed, and
  the password in the IDE's password store.
- Every Migrax command gets them as `MIGRAX_DATABASE_URL`, `MIGRAX_DATABASE_USER`,
  `MIGRAX_DATABASE_PASSWORD` and `MIGRAX_LOCATIONS`, so they win over the project's files and
  work with every Migrax version. A user or password left empty comes from the files.
- Clearing the URL also forgets the saved password.

## The tool window

Open **View > Tool Windows > Migrax**, or click the Migrax entry in the status bar.

| Tab | Shows | Same as |
|---|---|---|
| Status | Every migration and its state: applied, pending, failed, changed, missing. Double-click opens the migration; right-click also opens its rollback script. | [`migrax status`](reference/cli.md#status) |
| Plan | The SQL of the next migration, with its lint findings | [`migrax plan`](reference/cli.md#plan) |
| Lint | Locking and risky SQL in the pending migrations, or all of them. Double-click opens the file. | [`migrax lint`](reference/cli.md#lint) |
| Environments | Every configured database: in sync, pending, drift or unreachable | [`migrax environments`](reference/cli.md#environments) |

The tab on screen loads again after a change; the others load when you open them. This also
happens when an entity, migration or settings file changes outside the tool window (saved,
pulled or generated elsewhere), unless that check is turned off ([below](#in-the-editor)).

## The buttons

The buttons run down the left side of the tool window. The same actions are in
**Tools > Migrax**, and in a Migrax project also under **Migrax** when you right-click a file or
folder in the Project view, or a SQL, Java or Kotlin file in the editor. A button with nothing to do is greyed out, and its tooltip says why, such as
"No pending migrations at the last check".

| Button | What it does | Same as |
|---|---|---|
| Refresh | Loads the open tab again | |
| New Migration... | An empty migration and rollback script for hand-written SQL, such as a data change | [`migrax new`](reference/cli.md#new) |
| Generate Migration | Writes the next migration from your entity changes and opens it with its rollback script. It asks "Did you rename ...?" for a change that looks like a rename, and asks before dropping tables or columns, keeping a backup of the dropped data by default. | [`migrax generate`](reference/cli.md#generate) |
| Show Plan | Opens the Plan tab | [`migrax plan`](reference/cli.md#plan) |
| Migrate... | Shows the database and the pending migrations, then applies them | [`migrax migrate`](reference/cli.md#migrate) |
| Roll Back... | Shows the database and the newest applied migration, and the tables and columns that come back without their data (dropped by a migration made without a backup), then rolls it back | [`migrax rollback`](reference/cli.md#rollback) |
| Repair... | Removes migrations whose file was deleted from the history, and marks a failed one as applied or to run again | [`migrax repair`](reference/cli.md#repair) |
| Drift | Lists changes made to the database outside the migrations | [`migrax drift`](reference/cli.md#drift) |
| Doctor | Checks Java, the build, the entities and the database connection | [`migrax doctor`](reference/cli.md#doctor) |

Under **More**:

| Button | What it does | Same as |
|---|---|---|
| Generate for Zero Downtime | An expand migration now and a contract migration for the next release, so old and new versions of the application can run side by side | [`migrax generate --zero-downtime`](guides/zero-downtime.md) |
| Generate Contract | Adds the waiting contract migration once every instance runs the expand one | `migrax generate --contract` |
| Verify | Applies every migration to a throwaway database, checks the result against the entities and tests each rollback script. Your database is not touched. | [`migrax verify`](reference/cli.md#verify) |
| Report | Opens an HTML report of the schema, the migrations, the lint findings and the drift in your browser | [`migrax report`](reference/cli.md#report) |
| Backups... | Lists the backup tables of dropped data, and drops them once you no longer roll those migrations back | [`migrax backups`](reference/cli.md#backups) |

Migrate, Roll Back and Repair change the database, so they always show which database first and
ask. A database that is not on your computer gets a red warning. Once started, they cannot be
cancelled: stopping halfway could leave a migration half applied.

## In the editor

- **Entity classes:** a Migrax icon in the margin next to a Java or Kotlin entity class whose
  changes are not in a migration yet. Its tooltip lists the changes; a click offers **Generate
  Migration** or **Show Plan**. Turn it off in **Settings > Editor > General > Gutter Icons**.
- **Migration files:** a risky statement is underlined (red for errors), and its tooltip gives
  the [lint rule](reference/lint-rules.md), the message and the fix. The text in the editor is
  checked, also before you save it. **Alt+Enter** on a finding ignores it for that statement
  (`-- migrax:lint-ignore MX007` above it) or for the whole file, and for MX012 adds
  `-- migrax:no-transaction`. These aren't offered on a migration the database has already run:
  editing it would change its checksum.
- **Status bar:** "Migrax: 2 pending", "Migrax: in sync", with failed migrations and entity
  changes that are not in a migration yet. A click checks again and opens the tool window.

The status bar, the margin icons and the greyed-out buttons check again about 5 seconds after an
entity, migration or settings file changes. That check may build the project; turn it off in
**Settings > Tools > Migrax**.

## Untrusted projects

In a project the IDE doesn't trust (opened in Safe Mode), the plugin runs nothing: no status
check, no lint marks, no actions. A project's settings files can fill the database URL with your
environment variables and point it at another server, so opening a cloned repository must not
make Migrax connect anywhere. The status bar says "Migrax: not trusted" and the buttons are
greyed out. Trust the project with the Safe Mode banner, then click **Refresh** in the Migrax
tool window.

## Limitations

- With several Migrax projects below one IDE project, the first one found is used. Set up a
  further service with `migrax init` in a terminal, or open it as its own project.
- Migrate and Roll Back work on the database in the project's settings. Other
  [environments](guides/several-databases.md) show in the Environments tab but are migrated from
  the command line.
- Squash and merge are not in the IDE yet: use [`migrax squash`](reference/cli.md#squash) and
  [`migrax merge`](reference/cli.md#merge).
- In IDEs without Java support (PyCharm, WebStorm, GoLand, Rider and others) there is no icon
  next to entity classes. In a project without a Maven or Gradle build, Generate, Plan, Drift and
  Verify are greyed out: they compare entity classes.

## Troubleshooting

**"The Migrax command was not found."** Click **Download Migrax** in the notification, install
Migrax from the [Download page](download.md), or set its path in **Settings > Tools > Migrax**.
The settings page shows which command the plugin found.

**"The Migrax command set in Settings > Tools > Migrax does not exist."** The folder was moved
or deleted. Click **Use the Installed Migrax** or **Clear the Setting** in the notification
([details](#the-migrax-command)), or correct the path.

**"The path in Settings > Tools > Migrax is not a Migrax command."** The path points to a file
or folder without the launcher. Correct it, or clear it to use the one on `PATH` or a download.

**"Java 17 or newer is required."** The Migrax command runs on Java. Install a JDK 17 or newer,
or set `JAVA_HOME` to one, and restart the IDE.

**"Migrax could not be downloaded."** GitHub could not be reached: check the IDE's proxy
settings, or install Migrax from the [Download page](download.md).

**A button stays greyed out.** It reflects the last check. Click **Refresh**, or save a file to
check again.

**Anything else.** Click **Doctor**: it checks Java, the build, the entities and the database
connection and says what to fix. See also [Troubleshooting](reference/troubleshooting.md).
