#!/usr/bin/env bash
# Pulls each image named and says which release it is: its digest, when it was built, and the other tags
# Docker Hub gives that digest, as ghcr.io/falkordb/falkordb:latest is also 6.0.3. The nightly workflow
# runs it before the tests, so a run that fails on a new release names it. In GitHub Actions the table is
# also written to the job's summary. An image of ghcr.io has its tags looked up in the Docker Hub repository
# of the same name, which has the same digests.
#
#   scripts/report-test-images.sh <image>...
set -euo pipefail

summary="${GITHUB_STEP_SUMMARY:-/dev/null}"
{
    echo "| Image | Also tagged | Digest | Built |"
    echo "| --- | --- | --- | --- |"
} >> "$summary"

for image in "$@"; do
    docker pull -q "$image" > /dev/null
    digest="$(docker image inspect --format '{{index .RepoDigests 0}}' "$image" | sed 's/.*@//')"
    created="$(docker image inspect --format '{{.Created}}' "$image")"
    repo="${image%:*}"
    repo="${repo#ghcr.io/}"
    tag="${image##*:}"
    case "$repo" in */*) ;; *) repo="library/$repo" ;; esac
    # Best effort: the tags among the hundred updated last that share the digest, other than the one pulled,
    # and of those the plain versions (2026.10.1, 6.0.3) when there are any. A refused lookup, as at Docker
    # Hub's rate limit, says so, and not that the digest has no other tag.
    tags="$(curl -fsS "https://hub.docker.com/v2/repositories/$repo/tags?page_size=100&ordering=last_updated" 2>/dev/null \
        | jq -r --arg d "$digest" --arg t "$tag" '[.results[] | select(.digest == $d and .name != $t) | .name]
            | (map(select(test("^[0-9]+(\\.[0-9]+)+$")))) as $versions
            | if ($versions | length) > 0 then $versions else . end | join(", ")' 2>/dev/null)" \
        || tags="Docker Hub's tags not read"
    echo "$image: ${tags:-no other tag found} ($digest, built $created)"
    echo "| \`$image\` | ${tags:-} | \`${digest:0:19}\` | $created |" >> "$summary"
done
