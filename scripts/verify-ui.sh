#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME to JDK 27.0.1.0.3}"
: "${JETTY_HOME:?Set JETTY_HOME to Jetty 12.1.14}"
export PATH="$JAVA_HOME/bin:$PATH"
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx1024m}"
export NODE_OPTIONS=--max-old-space-size=1536
[[ "$(java -version 2>&1)" == *'"27.0.1.0.3"'* ]]
[[ "$(mvn -B -ntp -version)" == *'Apache Maven 3.10.0'* ]]
[[ "$(node --version)" == v24.22.2 ]]
[[ -f "$JETTY_HOME/lib/jetty-server-12.1.14.jar" ]]
revision=$(git rev-parse HEAD)
scripts/prepare-orm.sh
mvn -B -ntp install -DskipITs "-Dsource.revision=$revision"
mvn -B -ntp -f verification/ui/pom.xml clean package "-Dsource.revision=$revision"
mvn -B -ntp -f verification/ui/pom.xml exec:java -Dexec.mainClass=com.microsoft.playwright.CLI \
    -Dexec.classpathScope=test '-Dexec.args=install chromium'
container=""
jetty_pid=""
cleanup() {
    if [[ -n "$jetty_pid" ]]; then kill "$jetty_pid" 2>/dev/null || true; wait "$jetty_pid" || true; fi
    if [[ -n "$container" ]]; then docker rm -f "$container" >/dev/null; fi
}
trap cleanup EXIT
container=$(docker run --rm -d --memory=256m --tmpfs /var/lib/postgresql \
    -e POSTGRES_HOST_AUTH_METHOD=trust -e POSTGRES_USER=budgettest -e POSTGRES_DB=budgettest \
    -p 127.0.0.1::5432 postgres:18.6 -c timezone=UTC)
ready=false
for attempt in {1..60}; do
    if docker exec "$container" pg_isready -U budgettest -d budgettest >/dev/null 2>&1; then ready=true; break; fi
    sleep 1
done
[[ "$ready" == true ]]
port=$(docker port "$container" 5432/tcp | awk -F: '{print $NF}')
export BUDGET_TEST_URL="jdbc:postgresql://127.0.0.1:$port/budgettest" BUDGET_TEST_USER=budgettest BUDGET_UI_TEST=true
http_port=${BUDGET_UI_PORT:-18084}
export BUDGET_UI_URL="http://127.0.0.1:$http_port/"
mkdir -p target/ui-jetty
(
    cd target/ui-jetty
    java -jar "$JETTY_HOME/start.jar" --add-modules=http,ee11-deploy,ee11-annotations,ee11-websocket-jakarta > ../ui-jetty-setup.log 2>&1
    cp ../../verification/ui/target/budget-ui-test.war webapps/ROOT.war
    exec java -Xmx384m -Duser.timezone=Pacific/Honolulu -jar "$JETTY_HOME/start.jar" \
        jetty.http.host=127.0.0.1 "jetty.http.port=$http_port" \
        jetty.server.stopAtShutdown=true jetty.server.stopTimeout=5000
) > target/ui-jetty.log 2>&1 &
jetty_pid=$!
ready=false
for attempt in {1..90}; do
    kill -0 "$jetty_pid"
    if curl --fail --silent "$BUDGET_UI_URL" -o /dev/null; then ready=true; break; fi
    sleep 1
done
[[ "$ready" == true ]]
mvn -B -ntp -f verification/ui/pom.xml failsafe:integration-test failsafe:verify
