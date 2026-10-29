#!/usr/bin/env bash
# Publishes the version in gradle.properties to https://maven.eu.proximi.fi/releases/ as
# io.proximi.gadkit:proximiio-gadkit, from this computer. Run it from a clean checkout of a commit
# that is on origin. The repository does not accept a version twice, so a version is published once.
#
# MAVEN_USERNAME and MAVEN_PASSWORD (a token with write access) come from the shell, else from the
# login Keychain, else the script asks for them. Store them in the Keychain once; `security` asks for
# the password, so it is not in the shell history:
#
#     security add-generic-password -s maven.eu.proximi.fi -a <username> -w
#
# They stay in this process: never write them to a file in the repository.

set -euo pipefail
cd "$(dirname "$0")/.."

version="$(sed -nE 's/^gadkit.version=(.*)$/\1/p' gradle.properties)"
error() { echo "error: $1" >&2; exit 1; }

[ -z "$(git status --porcelain --untracked-files=no)" ] || error "the checkout has changes"
git fetch -q origin
[ -n "$(git branch -r --contains HEAD)" ] || error "HEAD $(git rev-parse --short HEAD) is not on origin"
grep -qF "### [${version}]" README.md || error "README.md has no '### [${version}]' heading"

keychain_service="maven.eu.proximi.fi"
if command -v security >/dev/null 2>&1; then
  if [ -z "${MAVEN_USERNAME:-}" ]; then
    MAVEN_USERNAME="$(security find-generic-password -s "$keychain_service" 2>/dev/null | sed -nE 's/^ *"acct"<blob>="(.*)"$/\1/p' || true)"
  fi
  if [ -z "${MAVEN_PASSWORD:-}" ] && [ -n "${MAVEN_USERNAME:-}" ]; then
    MAVEN_PASSWORD="$(security find-generic-password -s "$keychain_service" -a "$MAVEN_USERNAME" -w 2>/dev/null || true)"
    [ -z "$MAVEN_PASSWORD" ] || echo "MAVEN_USERNAME and MAVEN_PASSWORD from the login Keychain ($keychain_service)"
  fi
fi
[ -n "${MAVEN_USERNAME:-}" ] || read -r -p 'MAVEN_USERNAME: ' MAVEN_USERNAME
[ -n "${MAVEN_PASSWORD:-}" ] || { read -r -s -p 'MAVEN_PASSWORD: ' MAVEN_PASSWORD; echo; }
export MAVEN_USERNAME MAVEN_PASSWORD

read -r -p "Publish io.proximi.gadkit:proximiio-gadkit:${version} from $(git rev-parse --short HEAD)? [y/N] " answer
[ "$answer" = y ] || error "not published"

# Without the configuration cache, so the credentials are not stored in a cache entry.
./gradlew --no-configuration-cache \
  :proximiio-gadkit:testDebugUnitTest \
  :proximiio-gadkit:publishReleasePublicationToProximiioMavenRepository

echo "Published: https://maven.eu.proximi.fi/releases/io/proximi/gadkit/proximiio-gadkit/${version}/"
