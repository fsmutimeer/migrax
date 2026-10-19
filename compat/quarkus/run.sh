#!/usr/bin/env bash
# Builds the fixture Quarkus application in JVM or native mode with the migrax-quarkus extension
# from the local Maven repository, starts it and checks that Migrax applied the SQL and the Java
# migration before Hibernate validated the schema (strategy=validate). Then it adds a column by
# hand and checks that the drift check (migrax.drift.check=fail) stops the next start.
#
#   DB_URL=jdbc:postgresql://localhost:5432/postgres compat/quarkus/run.sh jvm|native
#
# Needs a PostgreSQL database at DB_URL (user postgres, password DB_PASSWORD or "postgres"),
# psql (or PSQL set to another command that takes -d <database> -qc <sql>) and, for native,
# Docker: the executable is built in Quarkus's Mandrel builder image.
set -euo pipefail
MODE="${1:?usage: run.sh jvm|native}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK:-$HERE/work}"
rm -rf "$WORK" && cp -r "$HERE/fixture" "$WORK" && cd "$WORK"

if [[ $MODE == native ]]; then
  mvn -B -q package -DskipTests -Dquarkus.native.enabled=true \
    -Dquarkus.native.container-build=true -Dquarkus.package.jar.enabled=false
  APP=(./target/quarkus-fixture-app-1.0-runner)
else
  mvn -B -q package -DskipTests
  APP=(java -jar target/quarkus-app/quarkus-run.jar)
fi

"${APP[@]}" > app.log 2>&1 &
PID=$!
BODY=""
for _ in $(seq 1 60); do
  BODY="$(curl -s localhost:8080/notes || true)"
  [[ -n $BODY ]] && break
  sleep 1
done
# The second database (migrax.databases.billing).
BILLING="$(curl -s localhost:8080/billing || true)"
kill "$PID" 2>/dev/null || true

echo "$MODE: /notes -> ${BODY:-nothing}, /billing -> ${BILLING:-nothing}"
if [[ $BODY != "from SQL,from Java" || $BILLING != "billing_team,from Java" ]] || grep -qiE "WARN.*(migrax|MigraxReadinessCheck)" app.log; then
  cat app.log
  exit 1
fi

# Drift at startup (migrax.drift.check=fail in the fixture): the build packaged .migrax, the
# start above found no drift; a change made by hand now stops the start.
PSQL="${PSQL:-psql -h localhost -U postgres}"
export PGPASSWORD="${DB_PASSWORD:-postgres}"
$PSQL -d "${DB_URL##*/}" -qc "ALTER TABLE note ADD COLUMN manual_change int"
STATUS=0
timeout 60 "${APP[@]}" > drift.log 2>&1 || STATUS=$?
$PSQL -d "${DB_URL##*/}" -qc "ALTER TABLE note DROP COLUMN manual_change"
if [[ $STATUS == 0 || $STATUS == 124 ]] || ! grep -q "manual_change" drift.log; then
  echo "$MODE: the application should have stopped because of the hand-made column"
  cat drift.log
  exit 1
fi
echo "$MODE: a hand-made column stops the start:"
grep -m1 "difference(s)" drift.log
