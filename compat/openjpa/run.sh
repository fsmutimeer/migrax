#!/usr/bin/env bash
# OpenJPA compatibility check. For each OpenJPA version, copies compat/openjpa/fixture,
# pins that version (3.x also switches the entities to javax.persistence), and runs doctor,
# generate, migrate, check and verify on H2. Then OpenJPA itself saves and reads every kind of
# entity against the migrated database (Smoke.java): OpenJPA has no schema validation, so
# this is the proof that the schema matches its mapping.
#
#   MIGRAX=<launcher> compat/openjpa/run.sh [versions]
MIGRAX="${MIGRAX:-migrax}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK:-$HERE/work}"
VERSIONS="${*:-3.2.2 4.1.1}"
failed=0

for v in $VERSIONS; do
  W="$WORK/$v"
  rm -rf "$W" && mkdir -p "$W" && cp -r "$HERE/fixture/." "$W/"
  cd "$W" || exit 1
  sed -i "s/OPENJPA_VERSION/$v/" pom.xml
  if [[ $v == 3.* ]]; then
    sed -i 's/jakarta\.persistence/javax.persistence/g' src/main/java/com/matrix/*.java
    sed -i 's#xmlns="https://jakarta.ee/xml/ns/persistence" version="3.0"#xmlns="http://xmlns.jcp.org/xml/ns/persistence" version="2.2"#' \
      src/main/resources/META-INF/persistence.xml
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
  # process-classes runs OpenJPA's enhancer first, as every OpenJPA build does.
  mvn -q -B process-classes exec:java -Dexec.mainClass=com.matrix.Smoke \
    -Dexec.args="$MIGRAX_DATABASE_URL" > smoke.log 2>&1
  smoke=$(grep -c "SMOKE OK" smoke.log)

  status=ok
  [[ $gen -ne 0 || $mig -ne 0 || $chk -ne 0 || $ver -ne 0 || $smoke -ne 1 ]] && status=FAILED
  [[ $reader != "OpenJPA $v"* ]] && status=FAILED
  [[ $status == FAILED ]] && failed=1
  printf '%-8s %-6s reader=[%s] generate=%s migrate=%s check=%s verify=%s openjpa=%s\n' \
    "$v" "$status" "${reader:-?}" "$gen" "$mig" "$chk" "$ver" \
    "$([[ $smoke -eq 1 ]] && echo ok || echo FAILED)"
  if [[ $status == FAILED ]]; then
    for log in doctor generate migrate check verify smoke; do
      echo "----- $v $log.log"; tail -n 20 "$log.log"
    done
  fi
done
exit $failed
