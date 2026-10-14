#!/usr/bin/env bash
# Builds the fixture Quarkus application in JVM or native mode with the migrax-quarkus extension
# from the local Maven repository, starts it and checks that Migrax applied the SQL and the Java
# migration before Hibernate validated the schema (strategy=validate).
#
#   DB_URL=jdbc:postgresql://localhost:5432/postgres compat/quarkus/run.sh jvm|native
#
# Needs a PostgreSQL database at DB_URL (user postgres, password DB_PASSWORD or "postgres") and,
# for native, Docker: the executable is built in Quarkus's Mandrel builder image.
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
kill "$PID" 2>/dev/null || true

echo "$MODE: /notes -> ${BODY:-nothing}"
if [[ $BODY != "from SQL,from Java" ]] || grep -qiE "WARN.*(migrax|MigraxReadinessCheck)" app.log; then
  cat app.log
  exit 1
fi
