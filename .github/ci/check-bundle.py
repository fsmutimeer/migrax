#!/usr/bin/env python3
"""Checks what a release would upload to Maven Central (saved by fake-central.py): exactly one
upload, with every published artifact, each with its jar, sources, javadoc and POM and a
signature (.asc) for every file, and nothing else. Central counts each upload as a release
and keeps every version forever.

    python3 .github/ci/check-bundle.py <folder> <version>
"""
import os
import sys
import zipfile

FOLDER, VERSION = sys.argv[1], sys.argv[2]
# artifactId -> file suffixes it publishes. Keep in line with the modules of
# .github/central/pom.xml (excludeArtifacts in parent/pom.xml).
JAR = ["-javadoc.jar", "-sources.jar", ".jar", ".pom"]
EXPECTED = {
    "migrax": JAR + [".zip"],
    "migrax-spring-boot-starter": JAR,
    "migrax-quarkus": JAR,
    "migrax-quarkus-deployment": JAR,
    "migrax-micronaut": JAR,
    "migrax-helidon": JAR,
    "migrax-jakarta-ee": JAR,
    "migrax-junit": JAR,
    "migrax-gradle-plugin": JAR,
    "org.migrax.gradle.plugin": [".pom"],
}
CHECKSUMS = (".md5", ".sha1", ".sha256", ".sha512")

uploads = sorted(f for f in os.listdir(FOLDER) if f.startswith("upload-"))
if len(uploads) != 1:
    sys.exit(f"error: {len(uploads)} uploads to Maven Central, expected exactly 1")

found = {}
try:
    bundle = zipfile.ZipFile(os.path.join(FOLDER, uploads[0]))
except zipfile.BadZipFile:
    sys.exit(f"error: {uploads[0]} is not a zip: the upload did not reach the stand-in whole")
with bundle:
    names = set(bundle.namelist())
    for name in sorted(names):
        parts = name.split("/")
        if name.endswith(("/", ".asc") + CHECKSUMS) or len(parts) < 4:
            continue
        if name + ".asc" not in names:
            sys.exit(f"error: {name} has no signature ({name}.asc)")
        artifact, version, file = parts[-3], parts[-2], parts[-1]
        if version != VERSION:
            sys.exit(f"error: {name} has version {version}, expected {VERSION}")
        found.setdefault(artifact, []).append(file[len(f"{artifact}-{VERSION}"):])

problems = [f"unexpected artifact {a}" for a in found if a not in EXPECTED]
for artifact, suffixes in EXPECTED.items():
    have = sorted(found.get(artifact, []))
    if have != sorted(suffixes):
        problems.append(f"{artifact}: has {have}, expected {sorted(suffixes)}")
if problems:
    sys.exit("error: the Maven Central bundle is not what a release publishes:\n  "
             + "\n  ".join(problems))
print(f"OK: one upload with {len(found)} artifacts, as a release publishes them.")
