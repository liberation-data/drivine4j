#!/usr/bin/env bash
# Copies a tagged version of the Cypher spec (github.com/liberation-data/drivine-cypher-spec) into
# src/test/resources/cypher, unchanged, and records the tag in VERSION there. Between tags it can copy a commit, by its
# full hash, which VERSION then names.
#
#   scripts/sync-cypher-spec.sh v0.1.0
set -euo pipefail

tag="${1:?usage: scripts/sync-cypher-spec.sh <tag>, such as v0.1.0}"
dir="$(cd "$(dirname "$0")/.." && pwd)/src/test/resources/cypher"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

curl -fsSL "https://github.com/liberation-data/drivine-cypher-spec/archive/$tag.tar.gz" | tar -xz -C "$tmp" --strip-components=1
cp "$tmp"/queries.yaml "$tmp"/writes.yaml "$tmp"/temporal.yaml "$tmp"/refusals.yaml "$tmp"/schema.yaml "$tmp"/host.yaml "$tmp"/store.yaml "$tmp"/marker.yaml "$tmp"/plans.yaml "$tmp"/search.yaml "$tmp"/results.yaml "$tmp"/fragments.yaml "$tmp"/vector.yaml "$tmp"/README.md "$dir"/
mkdir -p "$dir/search"
cp "$tmp"/search/docs-corpus.json "$dir/search/"
echo "$tag" > "$dir/VERSION"
echo "The Cypher spec in $dir is now $tag."
