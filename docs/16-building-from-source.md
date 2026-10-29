## Building from Source

```bash
# Run tests
./gradlew test

# Build library
./gradlew build

# Publish to local Maven (~/.m2/repository)
./gradlew publishToMavenLocal
```

Releases are published to Maven Central by CI from a `vX.Y.Z` tag: see [RELEASING.md](../RELEASING.md).

