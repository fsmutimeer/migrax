#!/usr/bin/env bash
# Deploys the fixture WAR with the migrax-jakarta-ee integration (from the local Maven
# repository) to an application server in Docker, and checks that Migrax applied the SQL and
# the Java migration to the server's own data source before the first request.
#
#   compat/jakarta-ee/run.sh wildfly|payara
#
# persistence.xml names java:comp/DefaultDataSource, the default data source every Jakarta EE
# server provides. WildFly serves the deployment from its virtual file system (vfs: URLs);
# Payara unpacks it and uses EclipseLink for JPA.
set -euo pipefail
SERVER="${1:?usage: run.sh wildfly|payara}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK:-$HERE/work}"
PORT="${PORT:-48080}"
rm -rf "$WORK" && cp -r "$HERE/fixture" "$WORK" && cd "$WORK"
mvn -B -q package
WAR="$WORK/target/app.war"
# Docker on Windows (Git Bash) needs a Windows path for the volume.
if command -v cygpath > /dev/null; then WAR="$(cygpath -w "$WAR")"; fi

case "$SERVER" in
  wildfly)
    IMAGE="quay.io/wildfly/wildfly:latest"
    MOUNT="/opt/jboss/wildfly/standalone/deployments/app.war"
    ;;
  payara)
    IMAGE="payara/server-full:6.2025.3-jdk21"
    MOUNT="/opt/payara/deployments/app.war"
    ;;
  *) echo "unknown server: $SERVER" >&2; exit 2 ;;
esac

NAME="migrax-jakarta-ee-$SERVER"
docker rm -f "$NAME" > /dev/null 2>&1 || true
MSYS_NO_PATHCONV=1 docker run -d --name "$NAME" -p "$PORT:8080" -v "$WAR:$MOUNT:ro" "$IMAGE" \
  > /dev/null
BODY=""
for _ in $(seq 1 90); do
  BODY="$(curl -s "localhost:$PORT/app/notes" || true)"
  [[ $BODY == from* ]] && break
  sleep 2
done
# The second database (migrax.databases.billing), defined by the application itself.
BILLING="$(curl -s "localhost:$PORT/app/billing" || true)"
docker logs "$NAME" > server.log 2>&1
docker rm -f "$NAME" > /dev/null

echo "$SERVER: /app/notes -> ${BODY:-nothing}, /app/billing -> ${BILLING:-nothing}"
if [[ $BODY != "from SQL,from Java" || $BILLING != "billing_team" ]]; then
  grep -iE "migrax|error|exception" server.log | head -40
  exit 1
fi
grep -E "Migrax: applied" server.log
