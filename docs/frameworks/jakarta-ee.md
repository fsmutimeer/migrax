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
data source is usually defined in the server's own configuration, which Migrax doesn't read:
set the connection with environment variables or options instead.

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

Run `migrax migrate` as a step of your deployment, before the new version starts.

!!! note "Other JPA providers"

    Migrax reads the mapping through Hibernate. With another JPA provider it scans the
    annotations and applies Hibernate's naming rules; check the generated names, and use
    `migrax drift --entities` to compare.
