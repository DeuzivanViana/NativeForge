#!/usr/bin/env bash
# Fetches the browser libraries of the remote web IDE from the npm registry, checks each
# tarball against vendor.sha256 and copies the files the page loads into
# src/main/resources/.../web/vendor/. The copies are committed; CI re-runs this script
# with --check to prove they match the pinned sources.
#
#   ./vendor.sh            refresh the vendored files
#   ./vendor.sh --check    fail when a vendored file differs from its pinned source
#   ./vendor.sh --pin      rewrite vendor.sha256 from the versions below (after an update)
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
out="$here/../src/main/resources/io/nativeforge/core/remote/web/vendor"
sums="$here/vendor.sha256"
mode="${1:-}"

# name@version  then  source-path:destination-path pairs (destinations relative to vendor/).
packages=(
  "@codemirror/state@6.7.6 dist/index.js:codemirror/state.js"
  "@codemirror/view@6.43.15 dist/index.js:codemirror/view.js"
  "@codemirror/language@6.13.1 dist/index.js:codemirror/language.js"
  "@codemirror/commands@6.11.1 dist/index.js:codemirror/commands.js"
  "@codemirror/autocomplete@6.20.4 dist/index.js:codemirror/autocomplete.js"
  "@codemirror/lint@6.9.8 dist/index.js:codemirror/lint.js"
  "@codemirror/search@6.7.2 dist/index.js:codemirror/search.js"
  "@codemirror/lang-cpp@6.0.3 dist/index.js:codemirror/lang-cpp.js"
  "@codemirror/streamparser@6.0.0 dist/index.js:codemirror/streamparser.js"
  "@codemirror/legacy-modes@6.5.5 mode/cmake.js:codemirror/legacy-modes/cmake.js mode/shell.js:codemirror/legacy-modes/shell.js mode/yaml.js:codemirror/legacy-modes/yaml.js mode/toml.js:codemirror/legacy-modes/toml.js mode/properties.js:codemirror/legacy-modes/properties.js mode/xml.js:codemirror/legacy-modes/xml.js mode/javascript.js:codemirror/legacy-modes/javascript.js mode/diff.js:codemirror/legacy-modes/diff.js mode/python.js:codemirror/legacy-modes/python.js"
  "@lezer/common@1.5.6 dist/index.js:lezer/common.js"
  "@lezer/highlight@1.2.5 dist/index.js:lezer/highlight.js"
  "@lezer/lr@1.4.12 dist/index.js:lezer/lr.js"
  "@lezer/cpp@1.1.6 dist/index.js:lezer/cpp.js"
  "style-mod@4.1.5 src/style-mod.js:misc/style-mod.js"
  "w3c-keyname@2.2.8 index.js:misc/w3c-keyname.js"
  "crelt@1.0.7 index.js:misc/crelt.js"
  "@marijn/find-cluster-break@1.0.4 src/index.js:misc/find-cluster-break.js"
  "@xterm/xterm@6.0.0 lib/xterm.mjs:xterm/xterm.mjs css/xterm.css:xterm/xterm.css"
  "@xterm/addon-fit@0.11.0 lib/addon-fit.mjs:xterm/addon-fit.mjs"
)

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
declare -A pinned=()
if [[ -f "$sums" ]]; then
  while read -r sum spec; do [[ -n "${sum:-}" && "$sum" != \#* ]] && pinned["$spec"]="$sum"; done < "$sums"
fi
new_sums=""
status=0

for entry in "${packages[@]}"; do
  read -r spec maps <<< "$entry"
  name="${spec%@*}"; version="${spec##*@}"
  tgz="$work/$(echo "$name" | tr '/@' '__').tgz"
  curl -fsSL --retry 3 -o "$tgz" "https://registry.npmjs.org/$name/-/${name##*/}-$version.tgz"
  sum="$(sha256sum "$tgz" | cut -d' ' -f1)"
  new_sums+="$sum  $spec"$'\n'
  if [[ "$mode" != "--pin" && "${pinned[$spec]:-}" != "$sum" ]]; then
    echo "checksum mismatch for $spec: got $sum, pinned ${pinned[$spec]:-none}" >&2; exit 1
  fi
  dir="$work/x"; rm -rf "$dir"; mkdir -p "$dir"; tar xzf "$tgz" -C "$dir"
  for map in $maps; do
    src="$dir/package/${map%%:*}"; dest="$out/${map##*:}"
    if [[ "$mode" == "--check" ]]; then
      cmp -s "$src" "$dest" || { echo "differs from $spec: $dest" >&2; status=1; }
    else
      mkdir -p "$(dirname "$dest")"; cp "$src" "$dest"
    fi
  done
  license="$out/licenses/$(echo "$name" | tr '/@' '__' | sed 's/^_//').LICENSE"
  if [[ "$mode" != "--check" ]]; then mkdir -p "$out/licenses"; cp "$dir/package/LICENSE" "$license"; fi
done

if [[ "$mode" == "--pin" ]]; then
  { echo "# sha256 of the npm tarballs vendor.sh copies files from (registry.npmjs.org)"; printf '%s' "$new_sums"; } > "$sums"
fi
exit $status
