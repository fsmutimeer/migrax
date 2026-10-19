---
seo_title: "Maven plugin for database migrations"
description: "Run every Migrax command as a Maven goal: mvn migrax:generate, migrax:migrate, migrax:check and more, from Maven Central."
---
# Maven plugin

Everything the command line does is also available as Maven goals, which is handy in CI or when
you prefer not to install the CLI.

## Setup

```xml title="pom.xml"
<build>
  <plugins>
    <plugin>
      <groupId>org.migrax</groupId>
      <artifactId>migrax</artifactId>
      <version>0.14.0</version>
    </plugin>
  </plugins>
</build>
```

Maven downloads it from Maven Central. Goals compile the project and use its runtime classpath,
so no other configuration is needed.

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
| `migrax:diff` | `migrax diff` (`-Dmigrax.source=<url>`) |
| `migrax:report` | `migrax report` |
| `migrax:rollback` | `migrax rollback` |
| `migrax:repair` | `migrax repair` |
| `migrax:baseline` | `migrax baseline` (`-Dmigrax.version=<migration>`) |
| `migrax:clean` | `migrax clean` |
| `migrax:backups` | `migrax backups` (`-Dmigrax.drop=true`, `-Dmigrax.migration=<number>`) |
| `migrax:new` | `migrax new` |
| `migrax:squash` | `migrax squash` |
| `migrax:merge` | `migrax merge` |
| `migrax:import` | `migrax import` (`-Dmigrax.from=flyway` or `liquibase`) |

## Options

Options are `-Dmigrax.*` properties (or `<configuration>` elements of the same name):

| Property | CLI option | Goals |
|---|---|---|
| `migrax.allowDestructive=true` | `--allow-destructive` | generate |
| `migrax.keepData=true` | `--keep-data` | generate |
| `migrax.renames=customers.email=contact_email` | `--rename` | generate |
| `migrax.renameTables=client=customer` | `--rename-table` | generate |
| `migrax.name=split_name` | `--name` | generate, new |
| `migrax.safe=true` | `--safe` | generate |
| `migrax.zeroDowntime=true` | `--zero-downtime` | generate |
| `migrax.contract=true` | `--contract` | generate |
| `migrax.dryRun=true` | `--dry-run` | migrate, rollback, clean, baseline |
| `migrax.resume=true` | `--resume` | migrate |
| `migrax.sql=true` | `--sql` | migrate |
| `migrax.lockTimeout=2m` | `--lock-timeout` | migrate, rollback, repair, clean, generate |
| `migrax.steps=2`, `migrax.to=<migration>` | `--steps`, `--to` | rollback (`to` also migrate) |
| `migrax.confirm=true` | `--yes` | rollback, repair, clean, baseline, backups |
| `migrax.action=applied` | `--action` | repair |
| `migrax.impact=true` | `--impact` | plan |
| `migrax.all=true`, `migrax.strict=true` | `--all`, `--strict` | lint |
| `migrax.verifyUrl=<jdbc-url>`, `migrax.skipRollbacks=true` | `--url`, `--skip-rollbacks` | verify |
| `migrax.entities=true` | `--entities` | drift |
| `migrax.html=target/migrax-report.html` | `--html` | report |
| `migrax.java=true` | `--java` | new |
| `migrax.url`, `migrax.user`, `migrax.password` | `--url`, `--user`, `--password` | all |
| `migrax.database=billing` | `--database` | all |
| `migrax.package`, `migrax.naming`, `migrax.dialect`, `migrax.locations`, `migrax.schemas` | same names | all |

Hints in the output use Maven syntax, for example:

```console
$ mvn migrax:generate
[ERROR] Review them, then run 'mvn migrax:generate -Dmigrax.allowDestructive=true'.
```
