## Building from Source

The Cypher compiler is [drivine-cypher-spec](https://github.com/liberation-data/drivine-cypher-spec)'s, at the version
`gradle/cypher-compiler.properties` pins. A release is read from Maven Central; a commit between releases
(`0.0.0-<commit>`) is built and published into the checkout. Run the script once, and again after the pin moves (it does
nothing for a release):

```bash
# The pinned Cypher compiler, where it is a commit's build, into .compiler-repo (a clone of drivine-cypher-spec is made in build/, or pass a checkout)
scripts/compiler-local.sh

# Run tests
./gradlew test

# Build library
./gradlew build

# Publish to local Maven (~/.m2/repository)
./gradlew publishToMavenLocal
```

Releases are published to Maven Central by CI from a `vX.Y.Z` tag: see [RELEASING.md](../RELEASING.md).

