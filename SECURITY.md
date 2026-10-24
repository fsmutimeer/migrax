# Security policy

## Reporting a vulnerability

Please don't open a public issue for a security problem. Report it privately on GitHub
instead: [Report a vulnerability](https://github.com/fsmutimeer/migrax/security/advisories/new)
(Security tab > "Report a vulnerability"). Only the maintainer sees the report.

Include what you found, the Migrax version, and the steps or input that show it. You get a
reply within a week. Once a fix is released, the advisory is published with credit to you,
unless you ask not to be named.

## What is covered

- The `migrax` command line tool, its launchers (`bin/migrax`, `bin/migrax.cmd`) and the
  installers (`install.sh`, `install.ps1`).
- The Maven plugin, the Gradle plugin and the framework integrations published as
  `org.migrax:*` on Maven Central.
- The container image `ghcr.io/fsmutimeer/migrax`, including the JDBC drivers it ships.
- The `migrax mcp` server.

Problems in a database, a JDBC driver or a framework that Migrax does not ship belong to that
project; tell us anyway if Migrax makes them easier to hit.

## Supported versions

Fixes go into a new release of the newest version line (for example 0.17.x while 0.17 is the
newest). Published versions are never changed; upgrade to the release that has the fix. The
[changelog](CHANGELOG.md) lists security fixes under "Security".

## How releases are checked

Before every release, every library Migrax names or ships is checked against the OSV
vulnerability database (`scripts/security-scan.py`), and the same check runs every week on
GitHub Actions. Releases on Maven Central are signed with GPG key `3203540753450CBC`. From
0.17.1 on, every file of a GitHub release has a signed build record
(`gh attestation verify <file> -R fsmutimeer/migrax`) and the release lists its libraries in
`migrax-<version>-sbom.cdx.json`; the container image carries its own software list and
build record.
