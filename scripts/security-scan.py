#!/usr/bin/env python3
"""Checks the libraries a Migrax release names or ships against the OSV vulnerability
database (osv.dev, which covers GitHub advisories and CVEs).

Usage (from the repository root):
    python3 -I scripts/security-scan.py                      # the local build's POMs
    python3 -I scripts/security-scan.py 0.17.0               # the POMs on Maven Central
    python3 -I scripts/security-scan.py --image migrax:scan  # also the jars in a built image

The local mode reads the .flattened-pom.xml files a build writes (run 'mvn install' for the
core and the integrations first). Only direct dependencies are checked.

Findings come in three groups, because they matter differently:
  SHIPPED   - inside something users run: the container image's JDBC drivers. Must be fixed.
  COMPILE   - a dependency the published POMs pull into users' builds. Must be fixed or
              explained.
  PROVIDED  - only compiled against; the user's application supplies its own version. Not
              shipped, but public scanners (mvnrepository) still flag it. Bump when safe.
Exit code: 0 clean, 1 a SHIPPED/COMPILE finding (or a version the scan can't read), 2 the scan
could not run (no POMs, Docker or osv.dev unreachable).
"""
import argparse
import glob
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import zipfile

ARTIFACTS = ["migrax", "migrax-quarkus", "migrax-quarkus-deployment",
             "migrax-spring-boot-starter", "migrax-micronaut", "migrax-helidon",
             "migrax-jakarta-ee", "migrax-junit", "migrax-gradle-plugin"]
CENTRAL = "https://repo1.maven.org/maven2/org/migrax"
OSV = "https://api.osv.dev/v1"
# Jars in the image that carry no Maven metadata: their group, by artifact name.
KNOWN_GROUPS = {"postgresql": "org.postgresql", "h2": "com.h2database",
                "checker-qual": "org.checkerframework"}


def fail(message):
    sys.exit(f"error: {message}")


def cannot_run(message):
    print(f"error: {message}", file=sys.stderr)
    sys.exit(2)


def fetch(url, data=None):
    """GET (or POST data) with a few retries; osv.dev and Central have short outages."""
    headers = {"Content-Type": "application/json"} if data else {}
    for attempt in range(3):
        try:
            request = urllib.request.Request(url, data, headers)
            return urllib.request.urlopen(request, timeout=120).read()
        except urllib.error.HTTPError as error:
            if error.code < 500:
                raise
            last = error
        except OSError as error:
            last = error
        time.sleep(5 * (attempt + 1))
    raise last


def dependencies(pom):
    props = {}
    block = re.search(r"<properties>(.*?)</properties>", pom, re.S)
    if block:
        props = dict(re.findall(r"<([\w.\-]+)>([^<]*)</\1>", block.group(1)))
    body = re.sub(r"<dependencyManagement>.*?</dependencyManagement>", "", pom, flags=re.S)
    body = re.sub(r"<build>.*?</build>", "", body, flags=re.S)
    for dep in re.findall(r"<dependency>(.*?)</dependency>", body, re.S):
        group = re.search(r"<groupId>(.*?)</groupId>", dep).group(1)
        artifact = re.search(r"<artifactId>(.*?)</artifactId>", dep).group(1)
        version = re.search(r"<version>(.*?)</version>", dep)
        scope = re.search(r"<scope>(.*?)</scope>", dep)
        if group == "org.migrax" or not version:
            continue
        value = re.sub(r"\$\{(.*?)\}", lambda m: props.get(m.group(1), m.group(0)),
                       version.group(1))
        yield group, artifact, value, scope.group(1) if scope else "compile"


def poms(version):
    found = []
    if version:
        for name in ARTIFACTS:
            url = f"{CENTRAL}/{name}/{version}/{name}-{version}.pom"
            try:
                found.append((name, fetch(url).decode()))
            except urllib.error.HTTPError as error:
                if error.code != 404:
                    cannot_run(f"{url}: {error}")
                print(f"note: {name} {version} is not on Maven Central")
            except OSError as error:
                cannot_run(f"could not reach Maven Central: {error}")
        if not found:
            cannot_run(f"version {version} is not on Maven Central")
    else:
        for path in sorted(glob.glob("**/.flattened-pom.xml", recursive=True)):
            text = open(path, encoding="utf-8").read()
            if "<packaging>pom</packaging>" not in text:
                found.append((path, text))
        if not found:
            cannot_run("no .flattened-pom.xml files: run 'mvn install' (core and "
                       "integrations) first")
    return found


def read_jars(drivers, image):
    jars = []
    for name in sorted(os.listdir(drivers)):
        groups = {}
        with zipfile.ZipFile(os.path.join(drivers, name)) as jar:
            for entry in jar.namelist():
                if entry.startswith("META-INF/maven/") and entry.endswith("pom.properties"):
                    text = jar.read(entry).decode("utf-8", "replace")
                    fields = dict(line.split("=", 1) for line in text.splitlines()
                                  if "=" in line and not line.startswith("#"))
                    if "groupId" in fields and "artifactId" in fields:
                        groups[fields["artifactId"].strip()] = fields["groupId"].strip()
        groups = {**KNOWN_GROUPS, **groups}
        # The file name has the exact version (mssql-jdbc-13.6.1.jre11.jar keeps its suffix).
        match = next(((a, name[len(a) + 1:-4]) for a in sorted(groups, key=len, reverse=True)
                      if name.startswith(a + "-")), None)
        if not match:
            fail(f"{name} in {image}: unknown library; add it to KNOWN_GROUPS")
        jars.append((groups[match[0]], match[0], match[1]))
    return jars


def image_jars(image):
    """Every jar in the image's drivers folder, transitive ones too."""
    folder = tempfile.mkdtemp(prefix="migrax-scan-")
    container = None
    try:
        container = subprocess.run(["docker", "create", image], capture_output=True,
                                   text=True, check=True).stdout.strip()
        subprocess.run(["docker", "cp", f"{container}:/opt/migrax/drivers", folder],
                       capture_output=True, text=True, check=True)
        return read_jars(os.path.join(folder, "drivers"), image)
    except subprocess.CalledProcessError as error:
        cannot_run(f"could not copy the jars out of {image}: {error.stderr.strip()}")
    except OSError as error:
        cannot_run(f"could not run docker: {error}")
    finally:
        if container:
            subprocess.run(["docker", "rm", "-f", container], capture_output=True)
        shutil.rmtree(folder, ignore_errors=True)


def osv(entries):
    query = {"queries": [{"package": {"ecosystem": "Maven", "name": f"{g}:{a}"}, "version": v}
                         for g, a, v in entries]}
    try:
        results = json.loads(fetch(f"{OSV}/querybatch", json.dumps(query).encode()))["results"]
    except OSError as error:
        cannot_run(f"could not reach osv.dev: {error}; run the scan again")
    return [[v["id"] for v in r.get("vulns", [])] for r in results]


def detail(advisory):
    try:
        data = json.loads(fetch(f"{OSV}/vulns/{advisory}"))
    except OSError:
        return f"{advisory}: details unavailable (see https://osv.dev/vulnerability/{advisory})"
    fixed = sorted({e["fixed"] for a in data.get("affected", []) for r in a.get("ranges", [])
                    for e in r.get("events", []) if "fixed" in e})
    severity = data.get("database_specific", {}).get("severity", "?")
    return f"{advisory} {severity}: {(data.get('summary') or '')[:80]} (fixed in {fixed[:4]})"


def main():
    parser = argparse.ArgumentParser(description="Check Migrax's dependencies against OSV.")
    parser.add_argument("version", nargs="?", help="a published version (default: local build)")
    parser.add_argument("--image", help="also check the jars in this built image")
    args = parser.parse_args()

    rows = []  # (group, source, g, a, v)
    for source, pom in poms(args.version):
        for g, a, v, scope in dependencies(pom):
            rows.append(("PROVIDED" if scope in ("provided", "test") else "COMPILE",
                         source, g, a, v))
    drivers = open("docker/drivers/pom.xml", encoding="utf-8").read()
    for g, a, v, _ in dependencies(drivers):
        rows.append(("SHIPPED", "container image drivers", g, a, v))
    if args.image:
        rows.extend(("SHIPPED", f"in image {args.image}", g, a, v)
                    for g, a, v in image_jars(args.image))

    unknown = [row for row in rows if "${" in row[4]]
    rows = [row for row in rows if "${" not in row[4]]
    found = osv([(g, a, v) for _, _, g, a, v in rows])
    blocking = 0
    for kind in ("SHIPPED", "COMPILE", "PROVIDED"):
        print(f"== {kind}")
        for (group, source, g, a, v), ids in zip(rows, found):
            if group != kind:
                continue
            print(f"  {'VULN' if ids else 'ok  '} {g}:{a}:{v}  ({source})")
            for advisory in ids:
                print("       " + detail(advisory))
            if ids and kind != "PROVIDED":
                blocking += 1
    for group, source, g, a, v in unknown:
        print(f"  UNKNOWN {g}:{a}:{v}  ({source}): version not resolved, not checked")
    blocking += len(unknown)
    print(f"\n{blocking} blocking finding(s) in SHIPPED/COMPILE.")
    print("Not covered: transitive dependencies of the POMs, the image's base OS packages and "
          "lib/migrax.jar's own code, and the GitHub Actions the workflows use.")
    sys.exit(1 if blocking else 0)


if __name__ == "__main__":
    main()
