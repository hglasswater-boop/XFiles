#!/usr/bin/env bash
# Publish a signed rolling APK without ever deleting a working release or tag.
set -euo pipefail

if [[ "$#" -ne 4 ]]; then
  echo "Usage: $0 {debug-latest|diagnostic-latest} apk title notes" >&2
  exit 2
fi

tag="$1"
apk="$2"
title="$3"
notes="$4"
: "${GITHUB_REPOSITORY:?}"
: "${GITHUB_SHA:?}"
case "$tag" in
  debug-latest|diagnostic-latest) ;;
  *) echo "Refusing to modify non-rolling tag: $tag" >&2; exit 2 ;;
esac
[[ -f "$apk" ]] || { echo "APK not found: $apk" >&2; exit 2; }
filename="$(basename -- "$apk")"

retry() {
  local attempt
  for attempt in 1 2 3 4; do
    if "$@"; then return 0; fi
    if (( attempt == 4 )); then
      echo "GitHub release operation failed after $attempt attempts: $1 $2" >&2
      return 1
    fi
    sleep "${RELEASE_RETRY_DELAY_SECONDS:-5}"
  done
}

get_assets() {
  gh release view "$tag" --json assets --jq '.assets[].name'
}

# Do not delete a previous, working release. A partial create may succeed on GitHub even
# when the client gets a 500; re-check after each failure before creating again.
if ! gh release view "$tag" --json id --jq .id >/dev/null 2>&1; then
  created=false
  for attempt in 1 2 3 4; do
    if gh release create "$tag" \
      --target "$GITHUB_SHA" \
      --title "$title" \
      --notes "$notes" \
      --prerelease; then
      created=true
      break
    fi
    if gh release view "$tag" --json id --jq .id >/dev/null 2>&1; then
      created=true
      break
    fi
    if (( attempt < 4 )); then sleep "${RELEASE_RETRY_DELAY_SECONDS:-5}"; fi
  done
  [[ "$created" == true ]] || { echo "Failed to ensure release $tag" >&2; exit 1; }
fi

# Only after this succeeds is it safe to remove obsolete APKs from this rolling release.
retry gh release upload "$tag" "$apk" --clobber
assets="$(retry get_assets)"
grep -Fxq -- "$filename" <<< "$assets" || {
  echo "Uploaded asset not visible in release $tag: $filename" >&2
  exit 1
}

while IFS= read -r old; do
  [[ -z "$old" || "$old" == "$filename" ]] && continue
  if ! retry gh release delete-asset "$tag" "$old" --yes; then
    # A GitHub 500 response may follow a successful deletion.
    remaining="$(retry get_assets)"
    if grep -Fxq -- "$old" <<< "$remaining"; then
      echo "Obsolete release asset still present: $old" >&2
      exit 1
    fi
  fi
done <<< "$assets"

retry gh release edit "$tag" \
  --target "$GITHUB_SHA" \
  --title "$title" \
  --notes "$notes" \
  --prerelease

# --target alone doesn't move an *existing* Git tag. Keep the rolling tag truthful.
retry gh api --method PATCH \
  "repos/$GITHUB_REPOSITORY/git/refs/tags/$tag" \
  -f "sha=$GITHUB_SHA" -F force=true

actual_sha="$(retry gh api -q '.object.sha' "repos/$GITHUB_REPOSITORY/git/ref/tags/$tag")"
[[ "$actual_sha" == "$GITHUB_SHA" ]] || {
  echo "Rolling tag $tag still points at $actual_sha, expected $GITHUB_SHA" >&2
  exit 1
}
assets="$(retry get_assets)"
[[ "$assets" == "$filename" ]] || {
  echo "Rolling release $tag assets differ from the current APK: $assets" >&2
  exit 1
}
echo "Published $tag at $GITHUB_SHA with $filename"
