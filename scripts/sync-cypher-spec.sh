#!/usr/bin/env bash
# Copies a tagged version of the Cypher spec (github.com/liberation-data/drivine-cypher-spec) into
# src/test/resources/cypher, unchanged, and records the tag in VERSION there.
#
#   scripts/sync-cypher-spec.sh v0.1.0
set -euo pipefail

tag="${1:?usage: scripts/sync-cypher-spec.sh <tag>, such as v0.1.0}"
dir="$(cd "$(dirname "$0")/.." && pwd)/src/test/resources/cypher"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

curl -fsSL "https://github.com/liberation-data/drivine-cypher-spec/archive/refs/tags/$tag.tar.gz" | tar -xz -C "$tmp" --strip-components=1
cp "$tmp"/queries.yaml "$tmp"/writes.yaml "$tmp"/refusals.yaml "$tmp"/README.md "$dir"/
echo "$tag" > "$dir/VERSION"
echo "The Cypher spec in $dir is now $tag."
