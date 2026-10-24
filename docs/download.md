---
seo_title: "Download Migrax"
description: "Download the Migrax command line tool for Windows, macOS and Linux, with checksums for every release. Needs Java 17 or newer."
hide:
  - feedback
  - navigation
---

# Download

Migrax needs **Java 17 or newer**. Download the command line tool below, then run its installer
as described in [Installation](getting-started/installation.md).

--8<-- "docs/downloads/releases.md"

## Container image

From Migrax 0.3.0 on, every release is also a container image with the common JDBC drivers,
for running migrations in Kubernetes or CI without a Java project:

```
docker pull ghcr.io/fsmutimeer/migrax:0.17.0
```

See [Containers and Kubernetes](guides/containers-and-kubernetes.md).

## Check your download

Each file's SHA-256 checksum is listed above and in `SHA256SUMS`. To check a file:

=== "Windows"

    ```bat
    certutil -hashfile migrax-0.17.0.zip SHA256
    ```

=== "macOS"

    ```bash
    shasum -a 256 migrax-0.17.0.zip
    ```

=== "Linux"

    ```bash
    sha256sum migrax-0.17.0.zip
    ```

The result must match the checksum on this page.

From Migrax 0.17.1 on, you can also check that a file was built by Migrax's own release
workflow on GitHub, from this repository's code, with the [GitHub CLI](https://cli.github.com):

```bash
gh attestation verify migrax-0.17.1.zip -R fsmutimeer/migrax
```

Each release also lists every library its files contain in `migrax-<version>-sbom.cdx.json`
(CycloneDX format), for security scanners and license checks. The container image keeps its
own software list and build record in the registry:

```bash
docker buildx imagetools inspect ghcr.io/fsmutimeer/migrax:0.17.1 --format '{{ json .SBOM }}'
docker buildx imagetools inspect ghcr.io/fsmutimeer/migrax:0.17.1 --format '{{ json .Provenance }}'
```

## Which file do I need?

- **To use the `migrax` command:** the `.zip`. It contains the command line tool and its
  installers.
- **The `.jar` files** are libraries for build tools and frameworks: the core library with the
  Maven plugin, the Gradle plugin, and the startup integrations for Spring Boot, Quarkus,
  Micronaut and Helidon. Maven and Gradle normally download these themselves; see
  [Frameworks](frameworks/index.md).
