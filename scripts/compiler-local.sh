#!/usr/bin/env bash
# Publishes the Cypher compiler that gradle/cypher-compiler.properties pins into .compiler-repo, the Maven repository
# in this checkout that the build reads it from, unless it is there already: a release is fetched from Maven
# Central, a commit between releases is built. CI runs this before Gradle; run it once in a new checkout, and again
# after the pin moves.
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
  *)
    # A release is on Maven Central, and the build would read it from there. Fetched here into .compiler-repo instead,
    # once, and kept by CI's cache of .compiler-repo, which is keyed on the pin: CI's parallel jobs each fetching it
    # from Central were refused now and then (429 Too Many Requests, #852). curl retries a refused request with a
    # growing wait. Should a file not come, nothing is put in .compiler-repo, and the build reads Central as before.
    if [ -f "$jar" ]; then
      echo "compiler-local: $version is in .compiler-repo"
      exit 0
    fi
    central="${COMPILER_LOCAL_CENTRAL:-https://repo.maven.apache.org/maven2}/org/drivine"
    fetched=$(mktemp -d)
    fetch() { mkdir -p "$fetched/$1/$version" && curl -fsSL --retry 8 -o "$fetched/$1/$version/$1-$version.$2" "$central/$1/$version/$1-$version.$2"; }
    # What the build reads of it, the JVM variant drivine4j depends on: its POM, its Gradle module and its jar.
    if fetch drivine-cypher-compiler-jvm pom && fetch drivine-cypher-compiler-jvm module && fetch drivine-cypher-compiler-jvm jar; then
      mkdir -p "$repo/org/drivine"
      cp -R "$fetched"/. "$repo/org/drivine/"
      echo "compiler-local: fetched release $version from Maven Central into .compiler-repo"
    else
      echo "compiler-local: could not fetch release $version from Maven Central; the build reads it from there" >&2
    fi
    rm -rf "$fetched"
    exit 0
    ;;
esac
# Before the published build is taken, so a commit moved without its version isn't built against the old one.
case "$version" in
  "0.0.0-${commit:0:12}") ;;
  *) echo "compiler-local: version $version is not 0.0.0-${commit:0:12}, the pinned commit's" >&2; exit 1 ;;
esac
if [ -f "$jar" ]; then
  echo "compiler-local: $version is in .compiler-repo"
  exit 0
fi
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
