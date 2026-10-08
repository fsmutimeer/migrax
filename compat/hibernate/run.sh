#!/usr/bin/env bash
# Hibernate compatibility matrix: for each Hibernate version, copies the fixture project, pins
# that version (Hibernate 5 also switches the entities to javax.persistence), then runs Migrax
# doctor, generate, migrate, check and verify on H2. verify replays the migrations on a fresh
# database, has that Hibernate version validate the schema, and round-trips the rollbacks.
#
# Usage: compat/hibernate/run.sh [version ...]     (default: the versions below)
# Set MIGRAX to the launcher to test (default: migrax on PATH). Exits 1 if any version fails.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
MIGRAX="${MIGRAX:-migrax}"
VERSIONS="${*:-5.4.33.Final 5.5.9.Final 5.6.15.Final 6.0.2.Final 6.1.7.Final 6.2.52.Final \
6.3.2.Final 6.4.10.Final 6.5.3.Final 6.6.58.Final 7.0.10.Final 7.1.36.Final 7.2.25.Final \
7.3.13.Final 7.4.12.Final}"
WORK="${WORK:-$HERE/work}"
failed=0

for v in $VERSIONS; do
  W="$WORK/$v"
  rm -rf "$W" && mkdir -p "$W" && cp -r "$HERE/fixture/." "$W/"
  cd "$W" || exit 1
  sed -i "s/HIBERNATE_VERSION/$v/" pom.xml
  if [[ $v == 5.* ]]; then
    sed -i 's#<groupId>org.hibernate.orm</groupId>#<groupId>org.hibernate</groupId>#' pom.xml
    sed -i 's/jakarta\.persistence/javax.persistence/g' src/main/java/com/matrix/*.java
  fi
  # A path the JVM understands on Windows (Git Bash) and elsewhere.
  dir="$(pwd -W 2>/dev/null || pwd)"
  export MIGRAX_DATABASE_URL="jdbc:h2:file:$dir/target/db" MIGRAX_DATABASE_USER=sa
  export MIGRAX_DATABASE_PASSWORD=

  "$MIGRAX" doctor > doctor.log 2>&1
  reader=$(grep -o -E "Entities: [0-9]+ table\(s\) under com.matrix \([^)]*\)" doctor.log \
    | sed 's/.*(//; s/)//')
  "$MIGRAX" generate --no-build --no-input > generate.log 2>&1; gen=$?
  "$MIGRAX" migrate --no-build > migrate.log 2>&1; mig=$?
  "$MIGRAX" check --no-build > check.log 2>&1; chk=$?
  "$MIGRAX" verify --no-build > verify.log 2>&1; ver=$?
  validation=$(grep -o -E "Schema matches the entities \([^)]*\)" verify.log \
    | sed 's/Schema matches the entities (//; s/)$//')

  # Hibernate 5.4+ and 6+ must be read through Hibernate's own mapping and validated by it.
  status=ok
  [[ $gen -ne 0 || $mig -ne 0 || $chk -ne 0 || $ver -ne 0 ]] && status=FAILED
  [[ $reader != "Hibernate $v mapping" ]] && status=FAILED
  [[ $validation != "Hibernate $v schema validation" ]] && status=FAILED
  [[ $status == FAILED ]] && failed=1
  printf '%-14s %-6s reader=[%s] generate=%s migrate=%s check=%s verify=%s validation=[%s]\n' \
    "$v" "$status" "${reader:-?}" "$gen" "$mig" "$chk" "$ver" "${validation:-?}"
  if [[ $status == FAILED ]]; then
    for log in doctor generate migrate check verify; do
      echo "----- $v $log.log"; tail -n 20 "$log.log"
    done
  fi
done
exit $failed
