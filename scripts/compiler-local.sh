#!/usr/bin/env bash
# Publishes the Cypher compiler that gradle/cypher-compiler.properties pins into .compiler-repo, the Maven repository
# in this checkout that the build reads it from, unless it is there already, or the pin is a release, which the build
# reads from Maven Central. CI runs this before Gradle; run it once in a new checkout, and again after the pin moves.
#
#   scripts/compiler-local.sh [<drivine-cypher-spec checkout>]
#
# With a checkout, the pinned commit is built from it (fetched into it first if it hasn't the commit); without one, a
# clone of liberation-data/drivine-cypher-spec is made in build/cypher-compiler-src. Either way the commit built is
# checked to be the pinned one.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
props="$root/gradle/cypher-compiler.properties"
commit=$(sed -n 's/^commit=//p' "$props")
version=$(sed -n 's/^version=//p' "$props")
repo="$root/.compiler-repo"
jar="$repo/org/drivine/drivine-cypher-compiler-jvm/$version/drivine-cypher-compiler-jvm-$version.jar"
case "$version" in
  0.0.0-*) ;;
  *) echo "compiler-local: $version is a release, read from Maven Central"; exit 0 ;;
esac
if [ -f "$jar" ]; then
  echo "compiler-local: $version is in .compiler-repo"
  exit 0
fi
case "$version" in
  "0.0.0-${commit:0:12}") ;;
  *) echo "compiler-local: version $version is not 0.0.0-${commit:0:12}, the pinned commit's" >&2; exit 1 ;;
esac
src=${1:-}
if [ -z "$src" ]; then
  src="$root/build/cypher-compiler-src"
  [ -d "$src/.git" ] || git clone -q --filter=blob:none https://github.com/liberation-data/drivine-cypher-spec.git "$src"
fi
git -C "$src" cat-file -e "$commit^{commit}" 2>/dev/null || git -C "$src" fetch -q origin "$commit"
# A worktree of its own, so a checkout given here is left on the branch it is on.
build="$root/build/cypher-compiler-$commit"
rm -rf "$build"
git -C "$src" worktree prune
git -C "$src" worktree add -q --detach "$build" "$commit"
[ "$(git -C "$build" rev-parse HEAD)" = "$commit" ] || { echo "compiler-local: $build is not at $commit" >&2; exit 1; }
(cd "$build/compiler" && ./gradlew --no-daemon -q publishJvmPublicationToMavenLocal publishKotlinMultiplatformPublicationToMavenLocal \
  -Pcompiler.version="$version" -Dmaven.repo.local="$repo")
git -C "$src" worktree remove --force "$build"
[ -f "$jar" ] || { echo "compiler-local: the build published no $jar" >&2; exit 1; }
echo "compiler-local: published $version from $commit into .compiler-repo"
