#!/usr/bin/env bash
set -euo pipefail
# The approved registry is not provisioned. Build the exact accepted ORM source locally.
revision=8daaf91d07d2e30b563322b22a0c6da7545578e5
source_dir=$(mktemp -d "${TMPDIR:-/tmp}/budget-orm.XXXXXX")
trap 'rm -rf "$source_dir"' EXIT
git clone --quiet https://github.com/finance-lab-project/orm.git "$source_dir"
git -C "$source_dir" -c advice.detachedHead=false checkout --quiet "$revision"
[[ "$(git -C "$source_dir" rev-parse HEAD)" == "$revision" ]]
mvn -B -ntp -f "$source_dir/pom.xml" org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
    -Dfile="$source_dir/build-support/build-parent-0.1.0.pom" \
    -DgroupId=org.financelab -DartifactId=build-parent -Dversion=0.1.0 -Dpackaging=pom
mvn -B -ntp -f "$source_dir/pom.xml" clean install -DskipITs "-Dsource.revision=$revision"
