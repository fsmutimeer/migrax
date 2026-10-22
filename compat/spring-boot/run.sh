#!/usr/bin/env bash
# Spring Boot starter check. For each Spring Boot version, starts a JPA application with
# migrax-spring-boot-starter on new H2 databases: Hibernate validates the schema, so the start
# succeeds only when the migration ran first, and a second database (migrax.databases.billing)
# is migrated too. JPA then saves a row and the migrax health component must report both
# databases UP. A second start must apply nothing and still be UP.
#
#   MIGRAX_COMPAT_STARTER_VERSION=<version in the local Maven repository> \
#     compat/spring-boot/run.sh [spring-boot-versions]
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK:-$HERE/work}"
VERSIONS="${*:-3.0.13 3.5.16 4.1.1}"
: "${MIGRAX_COMPAT_STARTER_VERSION:?set it to the migrax-spring-boot-starter version to test}"
failed=0

for v in $VERSIONS; do
  W="$WORK/$v"
  rm -rf "$W" && mkdir -p "$W" && cp -r "$HERE/fixture/." "$W/"
  cd "$W" || exit 1
  sed -i "s/BOOT_VERSION/$v/" pom.xml
  # A path the JVM understands on Windows (Git Bash) and elsewhere.
  dir="$(pwd -W 2>/dev/null || pwd)"
  start() {
    mvn -q -B -Dmigrax.version="$MIGRAX_COMPAT_STARTER_VERSION" compile exec:java \
      -Dexec.mainClass=com.matrix.Smoke -Dexec.args="$dir/target" > "$1" 2>&1
  }
  start first.log
  start second.log
  up="default=UP{applied=1, pending=0, problems=0} billing=UP{applied=1, pending=0, problems=0}"
  first=FAILED
  [[ $(grep -c "applied 1 migration(s)" first.log) -eq 2 ]] \
    && grep -q "STARTER OK books=1 health: $up" first.log && first=ok
  second=FAILED
  ! grep -q "applied 1 migration(s)" second.log \
    && grep -q "STARTER OK books=2 health: $up" second.log && second=ok

  status=ok
  [[ $first != ok || $second != ok ]] && status=FAILED && failed=1
  printf '%-7s %-6s first-start=%s second-start=%s\n' "$v" "$status" "$first" "$second"
  if [[ $status == FAILED ]]; then
    for log in first second; do
      echo "----- $v $log.log"; grep -v "^\s*at " "$log.log" | tail -n 25
    done
  fi
done
exit $failed
