---
hide:
  - navigation
---

# Download

Migrax needs **Java 17 or newer**. Download the command line tool below, then run its installer
as described in [Installation](getting-started/installation.md).

--8<-- "docs/downloads/releases.md"

## Check your download

Each file's SHA-256 checksum is listed above and in `SHA256SUMS`. To check a file:

=== "Windows"

    ```bat
    certutil -hashfile migrax-0.1.0.zip SHA256
    ```

=== "macOS"

    ```bash
    shasum -a 256 migrax-0.1.0.zip
    ```

=== "Linux"

    ```bash
    sha256sum migrax-0.1.0.zip
    ```

The result must match the checksum on this page.

## Which file do I need?

- **To use the `migrax` command:** the `.zip`. It contains the command line tool and its
  installers.
- **The `.jar` files** are libraries for build tools and frameworks: the core library with the
  Maven plugin, the Gradle plugin, and the startup integrations for Spring Boot, Quarkus,
  Micronaut and Helidon. Maven and Gradle normally download these themselves; see
  [Frameworks](frameworks/index.md).
