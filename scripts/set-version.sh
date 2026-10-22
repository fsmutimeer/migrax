#!/usr/bin/env bash
# Sets the Migrax version everywhere it is written down.
#
#   scripts/set-version.sh 0.2.0            # a release: also updates the version in the docs
#   scripts/set-version.sh 0.2.1-SNAPSHOT   # the next development version: build files only
#
# Changes, in every pom.xml: the project's own version and the versions of org.migrax
# artifacts (matched by an artifactId starting with "migrax", or the Gradle plugin marker
# "org.migrax.gradle.plugin", so third-party versions are never touched). Also the Gradle plugin's fallback version and the CI Gradle smoke test. For a release,
# the docs, README, examples and the CI templates in ci/ are moved from the latest release
# tag's version to the new one.
set -euo pipefail

NEW="${1:-}"
if [[ ! $NEW =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]]; then
  echo "usage: scripts/set-version.sh <major.minor.patch[-qualifier]>" >&2
  exit 1
fi
cd "$(dirname "$0")/.."

CURRENT=$(awk '/<artifactId>migrax<\/artifactId>/ {found=1} found && /<version>/ {
  gsub(/.*<version>|<\/version>.*/, ""); print; exit }' pom.xml)
echo "Build version: $CURRENT -> $NEW"

# Version that follows an org.migrax artifactId, in every pom.xml.
while IFS= read -r pom; do
  awk -v new="$NEW" '
    /<artifactId>(migrax[^<]*|org\.migrax\.gradle\.plugin)<\/artifactId>/ { pending = 1 }
    # A property such as ${migrax.version} is set by whoever builds that POM.
    pending && /<version>\$\{/ { pending = 0 }
    pending && /<version>[^<]*<\/version>/ {
      sub(/<version>[^<]*<\/version>/, "<version>" new "</version>"); pending = 0
    }
    { print }' "$pom" > "$pom.tmp" && mv "$pom.tmp" "$pom"
done < <(find . -name pom.xml -not -path "*/target/*" -not -path "./compat/*/work/*")

sed -i -E "s/(properties\.getProperty\(\"version\", \")[^\"]+(\"\))/\1$NEW\2/" \
  integrations/gradle-plugin/src/main/java/org/migrax/gradle/MigraxPlugin.java
sed -i -E "s/(id\(\"org\.migrax\"\) version \")[^\"]+/\1$NEW/" .github/workflows/ci.yml

if [[ $NEW != *-* ]]; then
  RELEASED=$(git describe --tags --abbrev=0 --match 'v[0-9]*' 2>/dev/null | sed 's/^v//' || true)
  RELEASED=${RELEASED:-$CURRENT}
  if [[ $RELEASED != "$NEW" ]]; then
    echo "Docs version: $RELEASED -> $NEW"
    old=${RELEASED//./\\.}
    # Lines saying when something arrived ("New in Migrax 0.13.0.", "From Migrax 0.3.0 on",
    # "published with Migrax 0.3.0 and newer") keep their version.
    history="(New in|[Ff]rom|[Ss]ince) Migrax $old|Migrax $old (and newer|or newer|and later|on\b)"
    { find docs README.md -name '*.md' -print0; find examples ci -type f -print0; } \
      | xargs -0 sed -i -E \
      -e "s/(fsmutimeer\/migrax\/v)$old\//\1$NEW\//g" \
      -e "s/(MIGRAX_VERSION[=: \"-]*)$old/\1$NEW/g" \
      -e "s/(<version>)$old(<\/version>)/\1$NEW\2/g" \
      -e "s/(org\.migrax:[a-z-]+:)$old/\1$NEW/g" \
      -e "s/(id\(\"org\.migrax\"\) version \")$old/\1$NEW/g" \
      -e "s/(fsmutimeer\/migrax@v)$old/\1$NEW/g" \
      -e "/$history/!s/(migrax |Migrax )$old/\1$NEW/g" \
      -e "s/(migrax-)$old/\1$NEW/g" \
      -e "s/(ghcr\.io\/fsmutimeer\/migrax:)$old/\1$NEW/g"
  fi
fi

echo "Done. Review with: git diff"
