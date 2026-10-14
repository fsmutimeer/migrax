# Hibernate versions

With Hibernate 5.0 or newer in your project, Migrax asks **your Hibernate version itself** for
the mapping, without connecting to a database. The generated schema is therefore exactly what
your application expects: names, types, join tables, element collections, inheritance and
sequences.

## Compatibility

Every version below is tested on each change to Migrax. A fixture project with identity and
sequence ids, enums, embedded types, element collections (sets, ordered lists, embeddables),
one-to-many, many-to-one and many-to-many relationships, single-table inheritance and
`@Version` is generated, migrated and verified. **That same Hibernate version then validates the
schema**, and the rollback scripts are tested.

| Hibernate | Entities | Typical frameworks | Status |
|---|---|---|---|
| 7.0 – 7.4 | `jakarta.persistence` | Spring Boot 4, recent Quarkus 3 releases, Micronaut 5 | <span class="mx-ok">Supported</span> |
| 6.3 – 6.6 | `jakarta.persistence` | Spring Boot 3.2 to 3.5, Quarkus 3, Micronaut 4, Helidon 4 | <span class="mx-ok">Supported</span> |
| 6.0 – 6.2 | `jakarta.persistence` | Spring Boot 3.0 and 3.1, early Quarkus 3 releases | <span class="mx-ok">Supported</span> |
| 5.3 – 5.6 | `javax.persistence` | Spring Boot 2.1 to 2.7 | <span class="mx-ok">Supported</span> |
| 5.0 – 5.2 | `javax.persistence` | Spring Boot 1.4 to 2.0 | <span class="mx-ok">Supported</span> (needs JAXB, see below) |
| before 5.0 | `javax.persistence` | Spring Boot 1.3 and older | Annotation scanning only |

Tested patch versions: 5.0.12, 5.1.17, 5.2.18, 5.3.36, 5.4.33, 5.5.9, 5.6.15, 6.0.2, 6.1.7,
6.2.52, 6.3.2, 6.4.10, 6.5.3, 6.6.58, 7.0.10, 7.1.36, 7.2.25, 7.3.13 and 7.4.12.

!!! note "Hibernate 5.0 to 5.2 need JAXB"

    These versions use JAXB, which Java 11 and newer no longer include. Migrax runs on Java 17,
    so add it to your project as your application on Java 11 or newer needs anyway:
    `javax.xml.bind:jaxb-api` and `org.glassfish.jaxb:jaxb-runtime` (2.3.x). Without it,
    Migrax reads your entities from their annotations and `verify` compares the schema itself,
    and both say that JAXB is missing.

## EclipseLink

When your project uses EclipseLink instead of Hibernate, Migrax asks **your EclipseLink** for
its mapping, again without connecting to a database. The migrations then have the names
EclipseLink uses (its default names such as `ID` and `Customer_SCORES`, written in lower case
except where the database's table names are case-sensitive, as on MySQL), and what its id
strategies need:

- `GenerationType.IDENTITY`: an identity column.
- `GenerationType.SEQUENCE`: a database sequence, started the way EclipseLink expects; on
  databases without sequences (MySQL), an identity column, as EclipseLink uses there.
- A plain `@GeneratedValue` (or `TABLE`): EclipseLink's `SEQUENCE` table and its row for each
  generator.

Tested versions: 2.7.15 (`javax.persistence`), 3.0.4 and 4.0.4 (`jakarta.persistence`). The
same fixture project is generated, migrated and verified, and then EclipseLink itself saves and
reads every kind of entity against the migrated database. EclipseLink has no schema validation,
so `verify` compares the schema with the mapping itself.

## OpenJPA

Projects that use OpenJPA (the JPA provider of Apache TomEE) are read through **your OpenJPA's**
own mapping, also without a database. The migrations get OpenJPA's names (the class and field
names as written, such as `Tag` and `createdAt`, in lower case except where the database's table
names are case-sensitive), the indexes OpenJPA puts on foreign keys, and its id strategies:
identity columns, database sequences, and `OPENJPA_SEQUENCE_TABLE` for a plain
`@GeneratedValue` (OpenJPA adds its row itself).

Tested versions: 3.2.2 (`javax.persistence`) and 4.1.1 (`jakarta.persistence`), with OpenJPA
saving and reading every kind of entity against the migrated database, as for EclipseLink.
OpenJPA has no sequences on MySQL and MariaDB: use `GenerationType.IDENTITY` there.

## Without Hibernate, EclipseLink or OpenJPA, or before Hibernate 5.0

Migrax then scans the JPA annotations and applies Hibernate 6's rules. That covers the common
mappings, but types and sequence names can differ from older Hibernate versions (Hibernate 5
uses one shared `hibernate_sequence`, for example). `migrax doctor` warns when this applies. Check
generated migrations carefully and run `migrax verify`.

## Choosing the reader

| `--extractor` | Behavior |
|---|---|
| `auto` (default) | Hibernate's mapping when possible, else EclipseLink's or OpenJPA's, else annotation scanning |
| `hibernate` | Hibernate's mapping only; fails with the reason if it can't be used |
| `eclipselink` | EclipseLink's mapping only; fails with the reason if it can't be used |
| `openjpa` | OpenJPA's mapping only; fails with the reason if it can't be used |
| `annotations` | Annotation scanning only |

`migrax doctor` shows which reader is used. `migrax inspect` prints the schema it read.
