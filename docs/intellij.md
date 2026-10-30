---
seo_title: "IntelliJ IDEA plugin for database migrations"
description: "The Migrax plugin for IntelliJ IDEA: generate, review, apply and roll back database migrations from JPA entities without leaving the editor. Free, for Community and Ultimate."
---
# IntelliJ IDEA plugin

The Migrax plugin brings Migrax into IntelliJ IDEA: a tool window with the state of every
migration, buttons for the everyday commands, an icon next to entity classes whose changes are
not in a migration yet, and lint findings marked inside migration files.

The plugin has no migration logic of its own. Every button runs the Migrax command line in the
background and shows its answer, so the IDE gives exactly the result your terminal and your CI
give.

!!! note "Not on the JetBrains Marketplace yet"
    The plugin is in early development. This page describes the coming first release.

## Requirements

| What | Needed |
|---|---|
| IDE | IntelliJ IDEA 2024.2 or newer, Community or Ultimate |
| Migrax | 0.17 or newer. Without one, the plugin offers to download it ([below](#the-migrax-command)) |
| Java | 17 or newer, for the Migrax command |
| Project | JPA/Hibernate entities and a `pom.xml` or `build.gradle`, as for the [command line](getting-started/quickstart.md) |

Some buttons need more:

- **Migrate, Roll Back, Repair, Drift, Backups** and the Status tab need the database in your
  settings to be reachable.
- **Verify** starts a throwaway database: in memory for H2, HSQLDB and Derby, in Docker for the
  others.
- **Kotlin entities** need the Kotlin plugin, which comes with IntelliJ IDEA.

## Install

1. In IntelliJ IDEA open **Settings > Plugins > Marketplace**, search for **Migrax** and click
   **Install**.
2. Open your service's project. The plugin finds Migrax projects by their `.migrax` folder,
   `migrax.properties` or `migrax.*` settings, also in a sub-folder up to three levels deep.
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

## The tool window

Open **View > Tool Windows > Migrax**, or click the Migrax entry in the status bar.

| Tab | Shows | Same as |
|---|---|---|
| Status | Every migration and its state: applied, pending, failed, changed, missing. Double-click opens the migration; right-click also opens its rollback script. | [`migrax status`](reference/cli.md#status) |
| Plan | The SQL of the next migration, with its lint findings | [`migrax plan`](reference/cli.md#plan) |
| Lint | Locking and risky SQL in the pending migrations, or all of them. Double-click opens the file. | [`migrax lint`](reference/cli.md#lint) |
| Environments | Every configured database: in sync, pending, drift or unreachable | [`migrax environments`](reference/cli.md#environments) |

The tab on screen loads again after a change; the others load when you open them.

## The buttons

The buttons run down the left side of the tool window. The same actions are in
**Tools > Migrax**. A button with nothing to do is greyed out, and its tooltip says why, such as
"No pending migrations at the last check".

| Button | What it does | Same as |
|---|---|---|
| Refresh | Loads the open tab again | |
| New Migration... | An empty migration and rollback script for hand-written SQL, such as a data change | [`migrax new`](reference/cli.md#new) |
| Generate Migration | Writes the next migration from your entity changes and opens it with its rollback script. It asks "Did you rename ...?" for a change that looks like a rename, and asks before dropping tables or columns, keeping a backup of the dropped data by default. | [`migrax generate`](reference/cli.md#generate) |
| Show Plan | Opens the Plan tab | [`migrax plan`](reference/cli.md#plan) |
| Migrate... | Shows the database and the pending migrations, then applies them | [`migrax migrate`](reference/cli.md#migrate) |
| Roll Back... | Shows the database and the newest applied migration, then rolls it back | [`migrax rollback`](reference/cli.md#rollback) |
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
  checked, also before you save it.
- **Status bar:** "Migrax: 2 pending", "Migrax: in sync", with failed migrations and entity
  changes that are not in a migration yet. A click checks again and opens the tool window.

The status bar, the margin icons and the greyed-out buttons check again about 5 seconds after an
entity, migration or settings file changes. That check may build the project; turn it off in
**Settings > Tools > Migrax**.

## Limitations

- With several Migrax projects below one IDE project, the first one found is used. Set up a
  further service with `migrax init` in a terminal, or open it as its own project.
- Migrate and Roll Back work on the database in the project's settings. Other
  [environments](guides/several-databases.md) show in the Environments tab but are migrated from
  the command line.
- Squash and merge are not in the IDE yet: use [`migrax squash`](reference/cli.md#squash) and
  [`migrax merge`](reference/cli.md#merge).
- Other JetBrains IDEs are not supported yet.

## Troubleshooting

**"The Migrax command was not found."** Click **Download Migrax** in the notification, install
Migrax from the [Download page](download.md), or set its path in **Settings > Tools > Migrax**.
The settings page shows which command the plugin found.

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
