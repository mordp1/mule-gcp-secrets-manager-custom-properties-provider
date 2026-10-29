#!/usr/bin/env bash
# Publishes the plugin to YOUR Anypoint Exchange, so apps can use it from Anypoint Studio / Code Builder
# and be built by CI without access to this repository.
#
# Usage: ./scripts/deploy-to-exchange.sh <ANYPOINT_ORG_ID> [version]
#
# Exchange requires the Maven groupId to be the organization ID, so this script publishes a copy of the
# POM with the groupId replaced. Credentials come from ~/.m2/settings.xml, server id "anypoint-exchange-v3":
#
#   <server>
#     <id>anypoint-exchange-v3</id>
#     <username>~~~Client~~~</username>                       <!-- Connected App -->
#     <password>CLIENT_ID~?~CLIENT_SECRET</password>
#   </server>
#
# For a different control plane (EU, Gov), set EXCHANGE_URL, e.g.
#   EXCHANGE_URL=https://maven.eu1.anypoint.mulesoft.com/api/v3/organizations
set -euo pipefail

ORG_ID="${1:?usage: $0 <ANYPOINT_ORG_ID> [version]}"
VERSION="${2:-}"
EXCHANGE_URL="${EXCHANGE_URL:-https://maven.anypoint.mulesoft.com/api/v3/organizations}"

cd "$(dirname "$0")/.."
# Replace only the project's own groupId (the first <groupId> after </parent>).
awk -v org="$ORG_ID" '
  /<\/parent>/ { after_parent = 1 }
  after_parent && !done && /<groupId>/ { sub(/<groupId>[^<]*<\/groupId>/, "<groupId>" org "</groupId>"); done = 1 }
  { print }
' pom.xml > pom.exchange.xml
trap 'rm -f pom.exchange.xml' EXIT

if [[ -n "$VERSION" ]]; then
  mvn -B -f pom.exchange.xml versions:set -DnewVersion="$VERSION" -DgenerateBackupPoms=false
fi

mvn -B -f pom.exchange.xml clean deploy \
  -DaltDeploymentRepository="anypoint-exchange-v3::${EXCHANGE_URL}/${ORG_ID}/maven"
