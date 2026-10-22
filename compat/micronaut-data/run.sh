#!/usr/bin/env bash
# Micronaut Data compatibility check. For each Micronaut platform version, copies
# compat/micronaut-data/fixture and runs doctor, generate, migrate, check and verify, then
# Micronaut Data itself saves, loads, updates and deletes every kind of entity against the
# migrated database (Smoke.java). Then an entity gets a new field: generate must write a second
# migration with it, and Micronaut Data must work again after migrate.
#
# Runs on H2, and also on PostgreSQL when MIGRAX_COMPAT_PG_URL is set (MIGRAX_COMPAT_PG_USER,
# MIGRAX_COMPAT_PG_PASSWORD; an empty database: its tables are dropped first). verify needs
# Docker for PostgreSQL. With MIGRAX_COMPAT_STARTUP_VERSION (a migrax-micronaut version in the
# local Maven repository), migrax-micronaut also migrates a new H2 database at startup.
# Micronaut 5 needs Java 25: run its versions with JAVA_HOME pointing at a JDK 25.
#
#   MIGRAX=<launcher> compat/micronaut-data/run.sh [platform-versions]
MIGRAX="${MIGRAX:-migrax}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK:-$HERE/work}"
VERSIONS="${*:-4.0.6 4.10.22 5.2.3}"
failed=0

# Platform version -> Micronaut core, Micronaut Data, Java release.
declare -A CORE=([4.0.6]=4.0.6 [4.4.3]=4.4.10 [4.10.22]=4.10.33 [5.2.3]=5.2.17)
declare -A DATA=([4.0.6]=4.0.4 [4.4.3]=4.7.1 [4.10.22]=4.14.5 [5.2.3]=5.2.3)
declare -A JAVA=([4.0.6]=17 [4.4.3]=17 [4.10.22]=17 [5.2.3]=25)

databases="h2"
[[ -n $MIGRAX_COMPAT_PG_URL ]] && databases="h2 postgresql"

for v in $VERSIONS; do
  if [[ -z ${DATA[$v]} ]]; then
    echo "$v: unknown platform version; add it to the table in run.sh"; failed=1; continue
  fi
  for db in $databases; do
    W="$WORK/$v-$db"
    rm -rf "$W" && mkdir -p "$W" && cp -r "$HERE/fixture/." "$W/"
    cd "$W" || exit 1
    sed -i "s/MN_PLATFORM/$v/; s/MN_CORE/${CORE[$v]}/; s/MN_DATA/${DATA[$v]}/; \
s/JAVA_RELEASE/${JAVA[$v]}/" pom.xml
    dialect=$([[ $db == h2 ]] && echo H2 || echo POSTGRES)
    sed -i "s/MN_DIALECT/$dialect/g" src/main/java/com/matrix/Smoke.java
    if [[ $db == h2 ]]; then
      # Micronaut Data 4.0 and 5 refuse sequences on H2 when they compile the entities; the
      # sequence is tested on PostgreSQL.
      sed -i 's/@GeneratedValue(GeneratedValue.Type.SEQUENCE)/@GeneratedValue/' \
        src/main/java/com/matrix/Values.java
    fi
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

    # A new field: a second migration, and Micronaut Data writes the new column.
    # Micronaut Data maps bean properties: the field needs its getter and setter.
    stock='  private int stock;\n  public int getStock() { return stock; }\n'
    stock+='  public void setStock(int stock) { this.stock = stock; }'
    sed -i "s/  private boolean published;/  private boolean published;\n$stock/" \
      src/main/java/com/matrix/Book.java
    mvn -q -B compile > compile.log 2>&1
    "$MIGRAX" generate --no-build --no-input --name stock > generate2.log 2>&1; gen2=$?
    grep -q -i "stock" src/main/resources/db/migration/0002_stock.sql 2>/dev/null || gen2=1
    "$MIGRAX" migrate --no-build > migrate2.log 2>&1; mig2=$?
    "$MIGRAX" check --no-build > check2.log 2>&1; chk2=$?
    ok2=$(smoke smoke2.log)

    # migrax-micronaut applies both migrations to a new H2 database at startup, before
    # Micronaut Data uses it. It is built against Micronaut 4.10, which Micronaut 4.0 to 4.9
    # don't load, so the step runs from 4.10 on.
    startup=skipped
    if [[ -n $MIGRAX_COMPAT_STARTUP_VERSION && $db == h2 && ($v == 4.1[0-9].* || $v == 5.*) ]]
    then
      mvn -q -B -Pstartup -Dmigrax.version="$MIGRAX_COMPAT_STARTUP_VERSION" compile exec:java \
        -Dexec.mainClass=com.matrix.Smoke -Dexec.args="jdbc:h2:file:$dir/target/startup sa" \
        > startup.log 2>&1
      startup=FAILED
      grep -q "applied 2 migration(s)" startup.log && grep -q "SMOKE OK" startup.log \
        && startup=ok
    fi

    status=ok
    [[ $gen -ne 0 || $mig -ne 0 || $chk -ne 0 || $ver -ne 0 || $ok1 -ne 1 ]] && status=FAILED
    [[ $gen2 -ne 0 || $mig2 -ne 0 || $chk2 -ne 0 || $ok2 -ne 1 ]] && status=FAILED
    [[ $reader != "Micronaut Data"* || $startup == FAILED ]] && status=FAILED
    [[ $status == FAILED ]] && failed=1
    printf '%-8s %-10s %-6s reader=[%s] generate=%s migrate=%s check=%s verify=%s ' \
      "$v" "$db" "$status" "${reader:-?}" "$gen" "$mig" "$chk" "$ver"
    printf 'micronaut=%s second-migration=%s micronaut-after=%s startup=%s\n' \
      "$([[ $ok1 -eq 1 ]] && echo ok || echo FAILED)" \
      "$([[ $gen2 -eq 0 && $mig2 -eq 0 && $chk2 -eq 0 ]] && echo ok || echo FAILED)" \
      "$([[ $ok2 -eq 1 ]] && echo ok || echo FAILED)" "$startup"
    if [[ $status == FAILED ]]; then
      for log in doctor generate migrate check verify smoke generate2 migrate2 check2 smoke2 \
          startup; do
        [[ -f $log.log ]] || continue
        echo "----- $v $db $log.log"; grep -v "^\s*at \|DEBUG" "$log.log" | tail -n 20
      done
    fi
  done
done
exit $failed
