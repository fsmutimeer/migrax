---
seo_title: "SQL migrations for Python, Node.js, Go, .NET, PHP and Ruby"
description: "Migrax applies hand-written SQL migrations for applications in any language, with history, checksums, locking, rollbacks and lint. One container image, no other tool."
---
# Any language

Migrax isn't only for Java. Any application that keeps its database changes as SQL files can
use it to apply them: Python, Node.js, Go, .NET, PHP, Ruby or anything else. You write the
migrations; Migrax runs them once and in order, records them, and refuses to run a file that
changed after it was applied. You don't need a second migration tool next to your framework.

| What you get | |
|---|---|
| Numbered migrations, applied once and in order | `0001_create_customer.sql`, `0002_add_email.sql` |
| Repeatable migrations for views and functions | `R__customer_view.sql`, run again when they change |
| A history table, checksums and a lock | Safe with several instances starting at the same time |
| Rollback scripts | `rollback/0002_add_email.sql`, run by `migrax rollback` |
| Checks before anything runs | `migrax lint` finds statements that lock tables or lose data |
| A status overview | `migrax status`, also as JSON for scripts |

The commands that compare the database with Java entities (`generate`, `check`, `drift` and
`verify`) have nothing to read in such a project; everything else works the same.

## Two ways to run it

| | Needs | Best for |
|---|---|---|
| [Container image](../guides/containers-and-kubernetes.md) | Docker | Docker Compose, Kubernetes, CI. Drivers for PostgreSQL, MariaDB, SQL Server, SQLite and H2 included |
| [Command line](../getting-started/installation.md) | Java 17 or newer | Running it on your machine like any other development tool |

## In three steps

1. Put your SQL files in a folder, for example `db/migrations`:

    ```sql title="db/migrations/0001_create_customer.sql"
    CREATE TABLE customer (id INT PRIMARY KEY, name VARCHAR(100) NOT NULL);
    ```

2. Tell Migrax the database, as a JDBC URL:

    ```bash
    export MIGRAX_DATABASE_URL=jdbc:postgresql://localhost:5432/shop
    export MIGRAX_DATABASE_USER=shop
    export MIGRAX_DATABASE_PASSWORD=secret
    export MIGRAX_LOCATIONS=filesystem:db/migrations
    ```

3. Apply the migrations:

    === "Container image"

        ```bash
        docker run --rm --network host \
          -v "$PWD/db/migrations:/migrations:ro" \
          -e MIGRAX_DATABASE_URL \
          -e MIGRAX_DATABASE_USER \
          -e MIGRAX_DATABASE_PASSWORD \
          ghcr.io/fsmutimeer/migrax:0.15.0 migrate
        ```

        The image reads the migrations from `/migrations`, so `MIGRAX_LOCATIONS` isn't needed
        there.

    === "Command line"

        ```console
        $ migrax migrate
        Applying migration '0001_create_customer.sql'...
        Successfully applied migration '0001_create_customer.sql'.
        Applied 1 migration(s); 1 total in db/migrations.
        ```

        The command line also needs the database's JDBC driver: see
        [Drivers](../guides/without-entities.md#drivers).

## Before your application starts

With Docker Compose, run Migrax as a service of its own and start the application only after it
finished:

```yaml title="compose.yaml"
services:
  db:
    image: postgres:17
    environment:
      POSTGRES_DB: shop
      POSTGRES_USER: shop
      POSTGRES_PASSWORD: secret
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U shop -d shop"]
      interval: 2s
      retries: 30

  migrate:
    image: ghcr.io/fsmutimeer/migrax:0.15.0
    command: migrate --lock-timeout 2m
    volumes:
      - ./db/migrations:/migrations:ro
    environment:
      MIGRAX_DATABASE_URL: jdbc:postgresql://db:5432/shop
      MIGRAX_DATABASE_USER: shop
      MIGRAX_DATABASE_PASSWORD: secret
    depends_on:
      db:
        condition: service_healthy

  app:
    build: .
    depends_on:
      migrate:
        condition: service_completed_successfully
```

When a migration fails, `migrate` exits with code 1 and the application doesn't start. In
Kubernetes, the same image runs as an init container or a Job: see
[Containers and Kubernetes](../guides/containers-and-kubernetes.md).

## From your project's scripts

Call Migrax from the task runner your project already has, so everyone runs the same command:

=== "Node.js"

    ```json title="package.json"
    {
      "scripts": {
        "db:migrate": "migrax migrate",
        "db:status": "migrax status",
        "db:new": "migrax new"
      }
    }
    ```

    `npm run db:new -- add_email` creates the next numbered file.

=== "PHP"

    ```json title="composer.json"
    {
      "scripts": {
        "db:migrate": "migrax migrate",
        "db:status": "migrax status"
      }
    }
    ```

=== "Python, Go, Ruby, .NET"

    ```makefile title="Makefile"
    db-migrate:
    	migrax migrate

    db-status:
    	migrax status
    ```

Settings in environment variables (or a `.env` file your shell loads) keep these scripts free of
passwords. Every setting is listed in [Configuration](../reference/configuration.md).

## In CI

Check the migrations on every pull request, before they reach a database:

```bash
migrax lint --strict
migrax migrate --dry-run
```

`lint` needs no database; `migrate --dry-run` connects and shows what would run. More in
[Continuous integration](../guides/ci.md).

## Next

- [SQL migrations for any language](../guides/without-entities.md): drivers, writing
  migrations, a database that already has tables, rollbacks and `migrax doctor`, step by step.
- [Moving existing migrations to Migrax](../guides/switching-tools.md), when your project
  already has a history of applied migrations.
- [Advanced migrations](../guides/advanced-migrations.md): placeholders, callbacks, environments
  and preconditions.
