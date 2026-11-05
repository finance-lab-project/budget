#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
java -version
mvn -version
scripts/prepare-orm.sh
container=""
cleanup() { if [[ -n "$container" ]]; then docker rm -f "$container" >/dev/null; fi; }
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
export BUDGET_TEST_URL="jdbc:postgresql://127.0.0.1:$port/budgettest"
export BUDGET_TEST_USER=budgettest
mvn -B -ntp clean verify "-Dsource.revision=$(git rev-parse HEAD)"
