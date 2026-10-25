---
seo_title: "Gradle plugin for database migrations"
description: "The Migrax Gradle plugin: generate, check and apply database migrations from Gradle tasks, with the plugin ID org.migrax."
---
# Gradle plugin

## Setup

```kotlin title="settings.gradle.kts"
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
```

```kotlin title="build.gradle.kts"
plugins {
    java
    id("org.migrax") version "0.17.1"
}

repositories {
    mavenCentral()          // the plugin loads the Migrax library from here
}

migrax {
    packageName.set("com.example.shop")   // optional; defaults to the project group
}
```

The plugin also adds `.migrax/snapshot.json` to the application's resources as
`META-INF/migrax/snapshot.json`, so the application can check for
[drift at startup](../guides/production-safety.md#drift-at-startup).

## Tasks

| Task | Same as |
|---|---|
| `migraxGenerate` | `migrax generate` |
| `migraxMigrate` | `migrax migrate` |
| `migraxStatus` | `migrax status` |
| `migraxPlan` | `migrax plan` |
| `migraxCheck` | `migrax check` |
| `migraxLint` | `migrax lint` |
| `migraxVerify` | `migrax verify` |
| `migraxDrift` | `migrax drift` |
| `migraxRollback` | `migrax rollback` |
| `migraxRepair` | `migrax repair` |
| `migraxNew` | `migrax new` |
| `migraxSquash` | `migrax squash` |
| `migraxMerge` | `migrax merge` |

Pass CLI options with `-Pmigrax.args`:

```bash
gradle migraxGenerate -Pmigrax.args="--allow-destructive"
gradle migraxRollback -Pmigrax.args="--steps 2 --yes"
```

## The `migrax` extension

| Property | Meaning |
|---|---|
| `packageName` | Entity package |
| `url`, `user`, `password` | Database connection (default: application config) |
| `locations` | Migration folders, comma-separated, e.g. `classpath:db/migration` |
| `naming` | `spring`, `jpa`, `jpa-snake` or `micronaut` |
| `dialect` | `postgresql`, `mysql`, `mariadb`, `sqlserver`, `oracle` or `h2` |
| `args` | Extra arguments for every task, e.g. `--verbose` |
| `version` | Migrax version used to run the tasks |
