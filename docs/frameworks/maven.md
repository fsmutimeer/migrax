# Maven plugin

Everything the command line does is also available as Maven goals, which is handy in CI or when
you prefer not to install the CLI.

## Setup

```xml title="pom.xml"
<build>
  <plugins>
    <plugin>
      <groupId>io.migrax</groupId>
      <artifactId>migrax</artifactId>
      <version>0.1.4</version>
    </plugin>
  </plugins>
</build>
```

Install it into your local Maven repository first (see
[Getting the integration libraries](index.md)). Goals compile the project and use its runtime
classpath, so no other configuration is needed.

## Goals

```bash
mvn migrax:generate
mvn migrax:migrate
mvn migrax:status
```

| Goal | Same as |
|---|---|
| `migrax:generate` | `migrax generate` |
| `migrax:migrate` | `migrax migrate` |
| `migrax:status` | `migrax status` |
| `migrax:plan` | `migrax plan` |
| `migrax:check` | `migrax check` |
| `migrax:lint` | `migrax lint` |
| `migrax:verify` | `migrax verify` |
| `migrax:drift` | `migrax drift` |
| `migrax:rollback` | `migrax rollback` |
| `migrax:repair` | `migrax repair` |
| `migrax:clean` | `migrax clean` |
| `migrax:new` | `migrax new` |
| `migrax:squash` | `migrax squash` |
| `migrax:merge` | `migrax merge` |
| `migrax:import` | `migrax import` (`-Dmigrax.from=flyway` or `liquibase`) |

## Options

Options are `-Dmigrax.*` properties (or `<configuration>` elements of the same name):

| Property | CLI option | Goals |
|---|---|---|
| `migrax.allowDestructive=true` | `--allow-destructive` | generate |
| `migrax.renames=customers.email=contact_email` | `--rename` | generate |
| `migrax.renameTables=client=customer` | `--rename-table` | generate |
| `migrax.name=split_name` | `--name` | generate, new |
| `migrax.safe=true` | `--safe` | generate |
| `migrax.dryRun=true` | `--dry-run` | migrate, rollback, clean |
| `migrax.resume=true` | `--resume` | migrate |
| `migrax.steps=2`, `migrax.to=<migration>` | `--steps`, `--to` | rollback |
| `migrax.confirm=true` | `--yes` | rollback, repair, clean |
| `migrax.action=applied` | `--action` | repair |
| `migrax.impact=true` | `--impact` | plan |
| `migrax.all=true`, `migrax.strict=true` | `--all`, `--strict` | lint |
| `migrax.verifyUrl=<jdbc-url>`, `migrax.skipRollbacks=true` | `--url`, `--skip-rollbacks` | verify |
| `migrax.entities=true` | `--entities` | drift |
| `migrax.java=true` | `--java` | new |
| `migrax.url`, `migrax.user`, `migrax.password` | `--url`, `--user`, `--password` | all |
| `migrax.package`, `migrax.naming`, `migrax.dialect`, `migrax.locations`, `migrax.schemas` | same names | all |

Hints in the output use Maven syntax, for example:

```console
$ mvn migrax:generate
[ERROR] Review them, then run 'mvn migrax:generate -Dmigrax.allowDestructive=true'.
```
