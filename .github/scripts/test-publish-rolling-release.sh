#!/usr/bin/env bash
# Contract tests for publish-rolling-release.sh. Uses a fake gh, no GitHub API access.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
publisher="$root/.github/scripts/publish-rolling-release.sh"
sandbox="$(mktemp -d)"
trap 'rm -rf "$sandbox"' EXIT
mkdir -p "$sandbox/bin"

cat > "$sandbox/bin/gh" <<'MOCK_GH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$MOCK_LOG"

if [[ "$1" == "release" ]]; then
  case "$2" in
    view)
      [[ -e "$MOCK_STATE/exists" ]] || exit 1
      if [[ " $* " == *" --json assets "* ]]; then
        cat "$MOCK_STATE/assets"
      else
        echo 12345
      fi
      ;;
    create)
      if [[ -e "$MOCK_STATE/create_partial_once" ]]; then
        rm "$MOCK_STATE/create_partial_once"
        touch "$MOCK_STATE/exists"
        echo "$GITHUB_SHA" > "$MOCK_STATE/tagsha"
        exit 1
      fi
      touch "$MOCK_STATE/exists"
      echo "$GITHUB_SHA" > "$MOCK_STATE/tagsha"
      ;;
    upload)
      [[ -e "$MOCK_STATE/exists" ]] || exit 1
      if [[ -e "$MOCK_STATE/upload_always_fails" ]]; then exit 1; fi
      if [[ -e "$MOCK_STATE/upload_fails_once" ]]; then
        rm "$MOCK_STATE/upload_fails_once"
        exit 1
      fi
      filename="$(basename -- "$4")"
      grep -Fxv "$filename" "$MOCK_STATE/assets" > "$MOCK_STATE/next" || true
      mv "$MOCK_STATE/next" "$MOCK_STATE/assets"
      echo "$filename" >> "$MOCK_STATE/assets"
      ;;
    edit)
      [[ -e "$MOCK_STATE/exists" ]] || exit 1
      ;;
    delete-asset)
      [[ -e "$MOCK_STATE/exists" ]] || exit 1
      grep -Fxv "$4" "$MOCK_STATE/assets" > "$MOCK_STATE/next" || true
      mv "$MOCK_STATE/next" "$MOCK_STATE/assets"
      ;;
    delete)
      echo "release deletion is forbidden" >&2
      exit 99
      ;;
    *)
      echo "Unexpected gh release command: $*" >&2; exit 98 ;;
  esac
elif [[ "$1" == "api" ]]; then
  if [[ " $* " == *" --method PATCH "* ]]; then
    for arg in "$@"; do
      if [[ "$arg" == sha=* ]]; then printf '%s\n' "${arg#sha=}" > "$MOCK_STATE/tagsha"; fi
    done
  elif [[ " $* " == *" -q .object.sha "* ]]; then
    cat "$MOCK_STATE/tagsha"
  else
    echo "Unexpected gh api command: $*" >&2; exit 98
  fi
else
  echo "Unexpected gh command: $*" >&2; exit 98
fi
MOCK_GH
chmod +x "$sandbox/bin/gh"

sha="0123456789abcdef0123456789abcdef01234567"
apk="$sandbox/XFiles-1.4.10-smb-b29900000-debug.apk"
printf 'test APK bytes\n' > "$apk"

new_case() {
  local name="$1"
  export MOCK_STATE="$sandbox/$name"
  export MOCK_LOG="$MOCK_STATE/gh.log"
  mkdir -p "$MOCK_STATE"
  : > "$MOCK_STATE/assets"
  : > "$MOCK_LOG"
}

publish() {
  env PATH="$sandbox/bin:$PATH" \
    GITHUB_REPOSITORY="example/XFiles" \
    GITHUB_SHA="$sha" \
    RELEASE_RETRY_DELAY_SECONDS=0 \
    bash "$publisher" "debug-latest" "$apk" "XFiles debug test" "Signed main APK"
}

assert_current() {
  [[ "$(cat "$MOCK_STATE/assets")" == "$(basename "$apk")" ]]
  [[ "$(cat "$MOCK_STATE/tagsha")" == "$sha" ]]
  ! grep -q '^release delete ' "$MOCK_LOG"
}

new_case "existing"
touch "$MOCK_STATE/exists"
echo "XFiles-old-debug.apk" > "$MOCK_STATE/assets"
echo "old-ref" > "$MOCK_STATE/tagsha"
publish
assert_current
grep -q '^release upload debug-latest ' "$MOCK_LOG"
grep -q '^release delete-asset debug-latest XFiles-old-debug.apk' "$MOCK_LOG"
! grep -q '^release create ' "$MOCK_LOG"

new_case "missing"
publish
[[ -f "$MOCK_STATE/exists" ]]
assert_current
grep -q '^release create debug-latest ' "$MOCK_LOG"

new_case "temporary-upload-error"
touch "$MOCK_STATE/exists" "$MOCK_STATE/upload_fails_once"
echo "prior.apk" > "$MOCK_STATE/assets"
echo "old-ref" > "$MOCK_STATE/tagsha"
publish
assert_current
[[ "$(grep -c '^release upload ' "$MOCK_LOG")" -eq 2 ]]

new_case "partial-create"
touch "$MOCK_STATE/create_partial_once"
publish
assert_current
[[ "$(grep -c '^release create ' "$MOCK_LOG")" -eq 1 ]]

new_case "permanent-api-error"
touch "$MOCK_STATE/exists" "$MOCK_STATE/upload_always_fails"
echo "working-previous.apk" > "$MOCK_STATE/assets"
echo "prior-ref" > "$MOCK_STATE/tagsha"
if publish; then
  echo "Publishing must fail when the upload can never succeed" >&2
  exit 1
fi
[[ "$(cat "$MOCK_STATE/assets")" == "working-previous.apk" ]]
[[ "$(cat "$MOCK_STATE/tagsha")" == "prior-ref" ]]
[[ "$(grep -c '^release upload ' "$MOCK_LOG")" -eq 4 ]]
! grep -q '^release delete ' "$MOCK_LOG"
! grep -q '^release delete-asset ' "$MOCK_LOG"

new_case "diagnostic"
touch "$MOCK_STATE/exists"
echo "previous-diagnostic.apk" > "$MOCK_STATE/assets"
env PATH="$sandbox/bin:$PATH" GITHUB_REPOSITORY="example/XFiles" \
  GITHUB_SHA="$sha" RELEASE_RETRY_DELAY_SECONDS=0 \
  bash "$publisher" "diagnostic-latest" "$apk" "Diagnostic test" "Side by side APK"
assert_current
grep -q '^release upload diagnostic-latest ' "$MOCK_LOG"

echo "Rolling release publisher contract tests passed"
