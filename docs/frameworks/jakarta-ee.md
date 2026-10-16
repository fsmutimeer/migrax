# Jakarta EE and plain Hibernate

Any project with JPA entities works, with or without an application framework.

## Database settings

Migrax reads the first persistence unit in `src/main/resources/META-INF/persistence.xml`:

```xml
<persistence-unit name="shop">
  <properties>
    <property name="jakarta.persistence.jdbc.url" value="jdbc:postgresql://localhost:5432/shop"/>
    <property name="jakarta.persistence.jdbc.user" value="shop"/>
    <property name="jakarta.persistence.jdbc.password" value="secret"/>
    <property name="hibernate.hbm2ddl.auto" value="none"/>
  </properties>
</persistence-unit>
```

`javax.persistence.jdbc.*` and `hibernate.connection.*` work too. On an application server, the
data source is usually defined in the server's own configuration, which the `migrax` command
can't read: give it the connection with environment variables or options, or let the
[startup integration](#migrate-at-startup) migrate inside the server.

```bash
export MIGRAX_DATABASE_URL=jdbc:postgresql://db:5432/shop
export MIGRAX_DATABASE_USER=shop
export MIGRAX_DATABASE_PASSWORD=secret
migrax migrate
```

## Naming

Plain Hibernate keeps names as written (`jpa` naming). A physical or implicit naming strategy in
`persistence.xml` (`hibernate.physical_naming_strategy`) is used when Migrax reads the mapping.

## Running migrations

Run `migrax migrate` as a step of your deployment, before the new version starts, or let the
application migrate when it starts on the server.

## Migrate at startup

Add the integration to your WAR or EAR:

```xml
<dependency>
  <groupId>org.migrax</groupId>
  <artifactId>migrax-jakarta-ee</artifactId>
  <version>0.10.0</version>
</dependency>
```

When the application starts, Migrax applies the migrations from `db/migration` to the data
source your `persistence.xml` names, as the server provides it. You don't repeat the
connection settings. It is tested on WildFly and Payara and uses only standard Jakarta EE (CDI
and JNDI), so it works on other servers too.

```xml
<persistence-unit name="shop">
  <jta-data-source>java:app/jdbc/shop</jta-data-source>
  <properties>
    <property name="jakarta.persistence.schema-generation.database.action" value="none"/>
  </properties>
</persistence-unit>
```

Keep the schema generation at `none`. The server starts JPA while it deploys the application,
before any application code runs, so Hibernate's `validate` would check the schema before the
migrations. Validate in CI with `migrax verify` instead.

| Setting | Default | Meaning |
|---|---|---|
| `migrax.enabled` | `true` | Apply migrations at startup |
| `migrax.datasource` | the data source `persistence.xml` names | JNDI name of the data source to migrate |
| `migrax.locations` | `db/migration` | Migration folders on the classpath, comma-separated |
| `migrax.java-package` | `db.migration` | Package of `JavaMigration` classes |
| `migrax.env` | | Environment: migrations marked `-- migrax:env <name>` run only there |
| `migrax.resume` | `false` | Re-run a failed migration marked `-- migrax:resume-safe` |
| `migrax.lock-timeout` | `0` | How long to wait while another instance migrates, for example `2m` |
| `migrax.placeholders.<name>` | | Values for `${name}` placeholders |
| `migrax.schemas` | | Schemas to migrate one after another |

Jakarta EE has no standard place for application settings, so Migrax reads them from
`migrax.properties` in the application (`src/main/resources`), from MicroProfile Config where
the server has it, from environment variables (`MIGRAX_DATASOURCE`, `MIGRAX_LOCATIONS`,
`MIGRAX_PLACEHOLDERS_<NAME>`, ...) and from system properties. Each one overrides the one
before.

!!! note "JPA providers"

    Migrax reads the mapping through the server's JPA provider: Hibernate (WildFly), EclipseLink
    (Payara, GlassFish) or OpenJPA (TomEE). With another JPA provider it scans the annotations
    and applies Hibernate's naming rules; check the generated names, and use
    `migrax drift --entities` to compare.
