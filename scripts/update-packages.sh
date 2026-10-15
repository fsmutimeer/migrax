#!/usr/bin/env bash
# Points the Homebrew tap and the Scoop bucket at a released version of Migrax.
#
#   scripts/update-packages.sh <version> <zip-sha256> [work-dir]
#
# Clones fsmutimeer/homebrew-migrax and fsmutimeer/scoop-migrax over SSH (the release workflow
# loads their deploy keys), rewrites the version, download URL and checksum, then commits and
# pushes. Set MIGRAX_PACKAGES_DRY_RUN=1 to change the clones without pushing.
set -euo pipefail

VERSION="$1"
SHA256="$2"
WORK="${3:-$(mktemp -d)}"
URL="https://github.com/fsmutimeer/migrax/releases/download/v$VERSION/migrax-$VERSION.zip"

case "$VERSION" in
  *-*) echo "update-packages: $VERSION is a pre-release; package managers get stable versions only" >&2
       exit 1 ;;
esac
[[ "$SHA256" =~ ^[0-9a-f]{64}$ ]] || { echo "update-packages: not a SHA-256: $SHA256" >&2; exit 1; }

update() {
  local repo="$1" file="$2" ssh_key="$3"
  local dir="$WORK/$repo"
  rm -rf "$dir"
  GIT_SSH_COMMAND="ssh -i $ssh_key -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new" \
    git clone -q "${MIGRAX_PACKAGES_REMOTE:-git@github.com:fsmutimeer}/$repo.git" "$dir"
  case "$file" in
    *.rb)
      sed -i -E "s#^  url \".*\"#  url \"$URL\"#; s#^  sha256 \".*\"#  sha256 \"$SHA256\"#" "$dir/$file"
      ;;
    *.json)
      sed -i -E "s#^    \"version\": \".*\",#    \"version\": \"$VERSION\",#; \
s#^    \"url\": \".*\",#    \"url\": \"$URL\",#; \
s#^    \"hash\": \".*\",#    \"hash\": \"$SHA256\",#; \
s#^    \"extract_dir\": \"migrax-[^\$\"]*\",#    \"extract_dir\": \"migrax-$VERSION\",#" "$dir/$file"
      ;;
  esac
  if ! grep -q "$SHA256" "$dir/$file" || ! grep -q "v$VERSION/migrax-$VERSION.zip" "$dir/$file"; then
    echo "update-packages: $repo/$file was not updated; check its format" >&2
    exit 1
  fi
  if git -C "$dir" diff --quiet; then
    echo "$repo is already at $VERSION"
    return
  fi
  git -C "$dir" -c user.name="Feroz Shah" -c user.email=ishpata@hotmail.com \
    commit -q -am "Migrax $VERSION"
  if [ -n "${MIGRAX_PACKAGES_DRY_RUN:-}" ]; then
    git -C "$dir" show --stat --format='%s' HEAD
  else
    GIT_SSH_COMMAND="ssh -i $ssh_key -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new" \
      git -C "$dir" push -q origin HEAD
    echo "$repo updated to $VERSION"
  fi
}

update homebrew-migrax Formula/migrax.rb "${HOMEBREW_TAP_KEY:-$HOME/.ssh/homebrew-migrax}"
update scoop-migrax bucket/migrax.json "${SCOOP_BUCKET_KEY:-$HOME/.ssh/scoop-migrax}"
