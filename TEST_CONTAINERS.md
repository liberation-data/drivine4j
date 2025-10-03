
## Using Test Containers (Default)

By default, tests will automatically start a Neo4j test container. No additional setup is required.

```bash
# Run tests with test containers (default)
./gradlew test
```

## Using Local Neo4j Instance

To bypass test containers and use a local Neo4j instance, activate the `local` Spring profile.
Local DB settings are in application.yml 

