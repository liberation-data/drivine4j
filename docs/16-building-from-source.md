## Building from Source

The Cypher compiler is built from [drivine-cypher-spec](https://github.com/liberation-data/drivine-cypher-spec), at the
commit `gradle/cypher-compiler.properties` pins, until it is on Maven Central. Publish it into the checkout once, and again
after the pin moves:

```bash
# The pinned Cypher compiler, into .compiler-repo (a clone of drivine-cypher-spec is made in build/, or pass a checkout)
scripts/compiler-local.sh

# Run tests
./gradlew test

# Build library
./gradlew build

# Publish to local Maven (~/.m2/repository)
./gradlew publishToMavenLocal
```

Releases are published to Maven Central by CI from a `vX.Y.Z` tag: see [RELEASING.md](../RELEASING.md).

