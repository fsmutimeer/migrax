#!/usr/bin/env bash
# Hibernate compatibility matrix: for each Hibernate version, copies the fixture project, pins
# that version (Hibernate 4 and 5 also switch the entities to javax.persistence), then runs Migrax
# doctor, generate, migrate, check and verify on H2. verify replays the migrations on a fresh
# database, has that Hibernate version validate the schema, and round-trips the rollbacks.
#
# Usage: compat/hibernate/run.sh [version ...]     (default: the versions below)
# Set MIGRAX to the launcher to test (default: migrax on PATH). Exits 1 if any version fails.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
MIGRAX="${MIGRAX:-migrax}"
VERSIONS="${*:-4.0.1.Final 4.1.12.Final 4.2.21.Final 4.3.11.Final 5.0.12.Final 5.1.17.Final 5.2.18.Final 5.3.36.Final 5.4.33.Final 5.5.9.Final \
5.6.15.Final 6.0.2.Final 6.1.7.Final 6.2.52.Final \
6.3.2.Final 6.4.10.Final 6.5.3.Final 6.6.58.Final 7.0.10.Final 7.1.36.Final 7.2.25.Final \
7.3.13.Final 7.4.12.Final}"
WORK="${WORK:-$HERE/work}"
failed=0

for v in $VERSIONS; do
  W="$WORK/$v"
  rm -rf "$W" && mkdir -p "$W" && cp -r "$HERE/fixture/." "$W/"
  cd "$W" || exit 1
  sed -i "s/HIBERNATE_VERSION/$v/" pom.xml
  if [[ $v == [45].* ]]; then
    sed -i 's#<groupId>org.hibernate.orm</groupId>#<groupId>org.hibernate</groupId>#' pom.xml
    sed -i 's/jakarta\.persistence/javax.persistence/g' src/main/java/com/matrix/*.java
    # @Comment is Hibernate 6+.
    sed -i '/@org.hibernate.annotations.Comment/d' src/main/java/com/matrix/*.java
    # Hibernate 5.0 to 5.2 need JAXB, which Java 11 and newer don't include; their
    # applications add it, and so does the fixture.
    if [[ $v == 5.[0-2].* ]]; then
      sed -i 's#<dependencies>#<dependencies><dependency><groupId>javax.xml.bind</groupId><artifactId>jaxb-api</artifactId><version>2.3.1</version></dependency><dependency><groupId>org.glassfish.jaxb</groupId><artifactId>jaxb-runtime</artifactId><version>2.3.9</version></dependency>#' pom.xml
    fi
  fi
  if [[ $v == 4.* ]]; then
    # Hibernate 4 predates java.time: its applications map dates with java.util.Date.
    sed -i '/import java.time/d' src/main/java/com/matrix/*.java
    sed -i 's/^\( *\)public Instant \(\w*\);/\1@Temporal(TemporalType.TIMESTAMP)\n\1public java.util.Date \2;/; s/^\( *\)public LocalDateTime \(\w*\);/\1@Temporal(TemporalType.TIMESTAMP)\n\1public java.util.Date \2;/; s/^\( *\)public LocalDate \(\w*\);/\1@Temporal(TemporalType.DATE)\n\1public java.util.Date \2;/' src/main/java/com/matrix/*.java
  fi
  if [[ $v == 4.[012].* ]]; then
    # @Table(indexes = ...) is JPA 2.1, which Hibernate 4.3 brought.
    sed -i 's/, indexes = @Index([^)]*)//' src/main/java/com/matrix/*.java
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

  # Every version must be read through Hibernate's own mapping and validated by it.
  status=ok
  [[ $gen -ne 0 || $mig -ne 0 || $chk -ne 0 || $ver -ne 0 ]] && status=FAILED
  [[ $reader != "Hibernate $v mapping" ]] && status=FAILED
  [[ $validation != "Hibernate $v schema validation" ]] && status=FAILED
  # The fixture's @Check, and its @Comment on Hibernate 6+, must be in the migration.
  grep -q "balance >= 0" src/main/resources/db/migration/*.sql || status=FAILED
  # And no other check: the ones Hibernate derives (enum, discriminator, list order) stay out.
  [[ $(cat src/main/resources/db/migration/*.sql | grep -o "CHECK (" | wc -l) -ne 1 ]] \
    && status=FAILED
  [[ $v != [45].* ]] && ! grep -q "People who buy" src/main/resources/db/migration/*.sql \
    && status=FAILED
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
