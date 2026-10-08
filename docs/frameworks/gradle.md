# Gradle plugin

## Setup

```kotlin title="settings.gradle.kts"
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
}
```

```kotlin title="build.gradle.kts"
plugins {
    java
    id("io.migrax") version "0.1.0"
}

migrax {
    packageName.set("com.example.shop")   // optional; defaults to the project group
}
```

Install the plugin into your local Maven repository first (see
[Getting the integration libraries](index.md)).

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
| `locations` | Migration folder, e.g. `classpath:db/migration` |
| `naming` | `spring`, `jpa`, `jpa-snake` or `micronaut` |
| `dialect` | `postgresql`, `mysql`, `mariadb`, `sqlserver`, `oracle` or `h2` |
| `args` | Extra arguments for every task, e.g. `--verbose` |
| `version` | Migrax version used to run the tasks |
