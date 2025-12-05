# Contributing to Drivine4j

## Running Tests Locally

```bash
# Run all tests
./gradlew test

# Run tests with coverage report
./gradlew test jacocoTestReport

# View coverage report
open build/reports/jacoco/test/html/index.html
```

## CI/CD Pipeline

The project uses GitHub Actions for continuous integration. The workflow runs on:
- Every push to `main` or `develop` branches
- Every pull request to `main` or `develop` branches

### What the CI Does

1. **Builds the project** using Gradle
2. **Runs all tests** against a Neo4j 5.28 service container
3. **Generates readable test reports** that appear directly in the GitHub UI
4. **Uploads test artifacts** including:
   - XML test results
   - HTML test reports (browsable)
   - Code coverage reports (JaCoCo)

### Reading Test Results

#### In the GitHub UI

After a workflow completes:

1. **Check Summary**: Click on the workflow run → See "Test Results" section with:
   - Total tests run
   - Passed/Failed/Skipped counts
   - List of all test cases with status
   - Detailed failure messages for failed tests

2. **Annotations**: Failed tests appear as annotations in the "Files changed" tab of PRs

#### Downloadable Reports

Test artifacts are available for 30 days:

1. Go to the workflow run
2. Scroll to "Artifacts" at the bottom
3. Download:
   - `test-report-html` - Full HTML report (open index.html)
   - `test-results` - XML results for import into IDEs
   - `coverage-report` - Code coverage HTML report

### Matrix Testing

The workflow tests against multiple configurations:
- Java 21 (primary)
- Gradle wrapper (primary) and Gradle 8.12.1

This ensures compatibility across different build environments.

## Test Requirements

- All PRs must have passing tests
- New features should include test coverage
- Bug fixes should include regression tests

## Code Coverage

- Coverage reports are generated automatically
- View them locally: `./gradlew jacocoTestReport && open build/reports/jacoco/test/html/index.html`
- View them in CI: Download the `coverage-report` artifact

## Local Neo4j Setup

Tests use Testcontainers by default (no local Neo4j needed).

If you want to run against a local Neo4j instance:

```bash
# Start Neo4j with Docker
docker run --rm \
  -p 7474:7474 -p 7687:7687 \
  -e NEO4J_AUTH=neo4j/testpassword \
  neo4j:5.28

# Run tests
./gradlew test
```

## Troubleshooting CI Failures

### Test Failures

1. Check the "Test Results" section in the workflow summary
2. Click on failed tests to see full stack traces
3. Download `test-report-html` for detailed HTML report

### Build Failures

1. Check the build logs in the workflow
2. Run the same Gradle command locally: `./gradlew build --no-daemon`
3. Ensure you have Java 21 installed

### Neo4j Connection Issues

If tests fail with connection errors:
- The workflow includes health checks for the Neo4j container
- Check the "Run tests" step logs for Neo4j startup issues
- Verify connection settings in test configuration
