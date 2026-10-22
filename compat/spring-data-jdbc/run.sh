#!/usr/bin/env bash
# Spring Data JDBC compatibility check. For each Spring Boot version (which brings its Spring
# Data JDBC), copies compat/spring-data-jdbc/fixture and runs doctor, generate, migrate, check and
# verify, then Spring Data JDBC itself saves, loads, updates and deletes every kind of aggregate
# against the migrated database (Smoke.java). Then an entity gets a new field: generate must
# write a second migration with it, and Spring Data JDBC must work again after migrate.
#
# Runs on H2, and also on PostgreSQL when MIGRAX_COMPAT_PG_URL is set (MIGRAX_COMPAT_PG_USER,
# MIGRAX_COMPAT_PG_PASSWORD; an empty database: its tables are dropped first). verify needs
# Docker for PostgreSQL. Spring Data JDBC quotes every name, so the two databases test both
# ways names are written. With MIGRAX_COMPAT_STARTER_VERSION (a migrax-spring-boot-starter
# version in the local Maven repository), the starter also migrates a new database at startup.
#
#   MIGRAX=<launcher> compat/spring-data-jdbc/run.sh [spring-boot-versions]
MIGRAX="${MIGRAX:-migrax}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK:-$HERE/work}"
VERSIONS="${*:-3.0.13 3.2.12 3.5.16 4.1.1}"
failed=0

databases="h2"
[[ -n $MIGRAX_COMPAT_PG_URL ]] && databases="h2 postgresql"

for v in $VERSIONS; do
  for db in $databases; do
    W="$WORK/$v-$db"
    rm -rf "$W" && mkdir -p "$W" && cp -r "$HERE/fixture/." "$W/"
    cd "$W" || exit 1
    sed -i "s/BOOT_VERSION/$v/" pom.xml
    # A path the JVM understands on Windows (Git Bash) and elsewhere.
    dir="$(pwd -W 2>/dev/null || pwd)"
    if [[ $db == h2 ]]; then
      export MIGRAX_DATABASE_URL="jdbc:h2:file:$dir/target/db" MIGRAX_DATABASE_USER=sa
      export MIGRAX_DATABASE_PASSWORD=
    else
      export MIGRAX_DATABASE_URL="$MIGRAX_COMPAT_PG_URL"
      export MIGRAX_DATABASE_USER="$MIGRAX_COMPAT_PG_USER"
      export MIGRAX_DATABASE_PASSWORD="$MIGRAX_COMPAT_PG_PASSWORD"
      "$MIGRAX" clean --yes > clean.log 2>&1
    fi
    smoke() {
      mvn -q -B compile exec:java -Dexec.mainClass=com.matrix.Smoke -Dexec.args="\
$MIGRAX_DATABASE_URL $MIGRAX_DATABASE_USER $MIGRAX_DATABASE_PASSWORD" > "$1" 2>&1
      grep -c "SMOKE OK" "$1"
    }

    "$MIGRAX" doctor > doctor.log 2>&1
    reader=$(grep -o -E "Entities: [0-9]+ table\(s\) under com.matrix \([^)]*\)" doctor.log \
      | sed 's/.*(//; s/)//')
    "$MIGRAX" generate --no-build --no-input --name initial > generate.log 2>&1; gen=$?
    "$MIGRAX" migrate --no-build > migrate.log 2>&1; mig=$?
    "$MIGRAX" check --no-build > check.log 2>&1; chk=$?
    "$MIGRAX" verify --no-build > verify.log 2>&1; ver=$?
    ok1=$(smoke smoke.log)

    # A new field: a second migration, and Spring Data JDBC writes the new column.
    sed -i 's/  public boolean vip;/  public boolean vip;\n  public int points;/' \
      src/main/java/com/matrix/Customer.java
    mvn -q -B compile > compile.log 2>&1
    "$MIGRAX" generate --no-build --no-input --name points > generate2.log 2>&1; gen2=$?
    grep -q -i "points" src/main/resources/db/migration/0002_points.sql 2>/dev/null || gen2=1
    "$MIGRAX" migrate --no-build > migrate2.log 2>&1; mig2=$?
    "$MIGRAX" check --no-build > check2.log 2>&1; chk2=$?
    ok2=$(smoke smoke2.log)

    # The starter applies both migrations to a new H2 database when the application starts,
    # before Spring Data JDBC uses it.
    startup=skipped
    if [[ -n $MIGRAX_COMPAT_STARTER_VERSION && $db == h2 ]]; then
      mvn -q -B -Pstarter -Dmigrax.version="$MIGRAX_COMPAT_STARTER_VERSION" compile exec:java \
        -Dexec.mainClass=com.matrix.Smoke -Dexec.args="jdbc:h2:file:$dir/target/startup sa" \
        > startup.log 2>&1
      startup=FAILED
      grep -q "applied 2 migration(s)" startup.log && grep -q "SMOKE OK" startup.log \
        && startup=ok
    fi

    status=ok
    [[ $gen -ne 0 || $mig -ne 0 || $chk -ne 0 || $ver -ne 0 || $ok1 -ne 1 ]] && status=FAILED
    [[ $gen2 -ne 0 || $mig2 -ne 0 || $chk2 -ne 0 || $ok2 -ne 1 ]] && status=FAILED
    [[ $reader != "Spring Data JDBC"* || $startup == FAILED ]] && status=FAILED
    [[ $status == FAILED ]] && failed=1
    printf '%-7s %-10s %-6s reader=[%s] generate=%s migrate=%s check=%s verify=%s ' \
      "$v" "$db" "$status" "${reader:-?}" "$gen" "$mig" "$chk" "$ver"
    printf 'spring=%s second-migration=%s spring-after=%s startup=%s\n' \
      "$([[ $ok1 -eq 1 ]] && echo ok || echo FAILED)" \
      "$([[ $gen2 -eq 0 && $mig2 -eq 0 && $chk2 -eq 0 ]] && echo ok || echo FAILED)" \
      "$([[ $ok2 -eq 1 ]] && echo ok || echo FAILED)" "$startup"
    if [[ $status == FAILED ]]; then
      for log in doctor generate migrate check verify smoke generate2 migrate2 check2 smoke2 \
          startup; do
        echo "----- $v $db $log.log"; tail -n 20 "$log.log"
      done
    fi
  done
done
exit $failed
