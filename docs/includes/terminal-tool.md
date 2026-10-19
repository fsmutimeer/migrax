**The tool for your terminal**, pick one:

=== "Maven plugin"

    ```xml title="pom.xml"
    <build>
      <plugins>
        <plugin>
          <groupId>org.migrax</groupId>
          <artifactId>migrax</artifactId>
          <version>0.15.0</version>
        </plugin>
      </plugins>
    </build>
    ```

    Then run `mvn migrax:generate`, `mvn migrax:status` and so on in the project folder. See
    [Maven plugin](maven.md).

=== "Gradle plugin"

    ```kotlin title="build.gradle.kts"
    plugins {
        id("org.migrax") version "0.15.0"
    }
    ```

    Then run `./gradlew migraxGenerate`, `./gradlew migraxStatus` and so on. See
    [Gradle plugin](gradle.md).

=== "Command line tool"

    Install `migrax` once per machine ([Installation](../getting-started/installation.md)),
    then run `migrax generate`, `migrax status` and so on in the project folder. It works with
    any build, and nothing is added to the project.

**Only one of the two?**

- **Without the startup library** the application doesn't migrate by itself: run `migrate`
  yourself or in the deployment pipeline (`mvn migrax:migrate`, `./gradlew migraxMigrate`,
  `migrax migrate` or the [container image](../guides/containers-and-kubernetes.md)).
- **Without a tool for your terminal** there's no `generate`: write the migration files
  yourself, and the startup library still applies them.
