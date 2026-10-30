#!/usr/bin/env bash
# One-word ship: `ship v1.0.0` — bumps version, commits, tags, pushes.
set -euo pipefail
cd "$(dirname "$0")"

TAG="${1:-}"
[[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || {
  echo "usage: ship v<major>.<minor>.<patch>"; exit 1;
}
VER="${TAG#v}"
KTS=app/build.gradle.kts

CODE=$(grep -oP 'versionCode = \K[0-9]+' "$KTS" | head -1)
NEWCODE=$((CODE + 1))
sed -i "s/versionCode = $CODE/versionCode = $NEWCODE/" "$KTS"
sed -i "s/versionName = \"[^\"]*\"/versionName = \"$VER\"/" "$KTS"

PREV_TAG=$(git describe --tags --abbrev=0 2>/dev/null || true)
{
  echo "## $TAG — $(date +%Y-%m-%d) (versionCode $NEWCODE)"
  echo
  if [ -n "$PREV_TAG" ]; then
    git log --pretty='- %s' "$PREV_TAG"..HEAD
  else
    git log --pretty='- %s' HEAD
  fi
  echo
  cat RELEASE_NOTES.md 2>/dev/null || true
} > .release-notes.tmp && mv .release-notes.tmp RELEASE_NOTES.md

git add -A
git commit -m "release $TAG (versionCode $NEWCODE)"
git tag -a "$TAG" -m "NativeEQ $TAG"
git push origin HEAD || true   # github ref-lock flake: verify state after
for i in 1 2 3; do
  git push origin "$TAG" && break || { echo "tag push retry $i"; sleep 3; }
done
echo "shipped $TAG — CI will publish equalizer-release.apk to GitHub Releases"
