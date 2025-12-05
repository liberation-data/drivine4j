# Understanding Test Reports in GitHub Actions

This project uses enhanced test reporting to make CI test results easy to read and debug.

## Where to Find Test Results

### 1. Pull Request Checks Tab

When you open a PR, go to the "Checks" tab to see:

- ✅ Overall pass/fail status
- 📊 Test summary (X passed, Y failed, Z skipped)
- 📝 List of all test cases with their status
- 🔍 Click any failed test to see the full error message and stack trace

### 2. Workflow Summary Page

Click on any workflow run to see the summary:

```
Test Results
────────────────────────────────
✅ 156 tests passed
❌ 2 tests failed
⊘ 3 tests skipped

Total: 161 tests in 12.3s

Failed Tests:
├─ PersonRepositoryTest
│  └─ ❌ should handle null bio field
│     Expected: null
│     Actual: "default bio"
│     at PersonRepositoryTest.kt:142
│
└─ GraphObjectManagerTest
   └─ ❌ should cascade delete orphaned nodes
      Expected collection size: 0
      Actual: 1
      at GraphObjectManagerTest.kt:305
```

### 3. Annotations in "Files Changed"

Failed tests appear as annotations directly in your code:

- Red error markers on the relevant lines
- Click to see the failure message
- Helps you quickly identify what needs fixing

### 4. Downloadable Artifacts

At the bottom of each workflow run:

#### `test-report-html` (Recommended)
Download this for the full browseable HTML report:
1. Extract the ZIP
2. Open `index.html` in your browser
3. Navigate through test suites, classes, and methods
4. See execution times, stack traces, and system output

#### `test-results`
Raw XML files (JUnit format) for:
- Importing into IntelliJ IDEA
- Integration with other CI tools
- Parsing with custom scripts

#### `coverage-report`
JaCoCo HTML coverage report showing:
- Line coverage percentages by package/class
- Highlighted source code (green = covered, red = not covered)
- Branch coverage analysis

## What Makes Our CI Test Reports Better

### Traditional CI Test Output (Hard to Read)

```
org.drivine.manager.PersonRepositoryTest > should handle null bio field FAILED
    java.lang.AssertionError: expected:<null> but was:<default bio>
        at org.junit.Assert.fail(Assert.java:89)
        at org.junit.Assert.failNotEquals(Assert.java:835)
        ... 45 more lines ...
```

Buried in logs, no summary, hard to navigate.

### Our Enhanced CI Test Reports (Easy to Read)

1. **Visual Summary**: See pass/fail counts at a glance
2. **Grouped by Suite**: Tests organized by class/package
3. **Interactive**: Click to expand/collapse details
4. **Direct Links**: Jump from failed test to source code
5. **Persistent**: Reports available for 30 days

## Reading a Failed Test

When a test fails, the report shows:

```
❌ PersonRepositoryTest > should handle null bio field

Duration: 1.2s

Failure Message:
Expected: null
Actual: "default bio"

Stack Trace:
org.opentest4j.AssertionFailedError: expected:<null> but was:<default bio>
    at PersonRepositoryTest.kt:142
    at PersonRepository.findById(PersonRepository.kt:89)
```

You get:
- **Test name** and class
- **Execution time** (helps identify slow tests)
- **Clear failure message** (what went wrong)
- **Stack trace** with line numbers (where it failed)
- **File links** in GitHub that jump to the code

## Code Coverage Report

Open `coverage-report/index.html` to see:

### Package Level
```
org.drivine.manager     87% (523/600 instructions)
org.drivine.query       92% (412/448 instructions)
org.drivine.session     78% (234/300 instructions)
```

### Class Level
Click a package to see coverage by class:
```
PersonRepository        95% (114/120 instructions)
GraphObjectManager      85% (204/240 instructions)
SessionManager          72% (108/150 instructions)
```

### Line Level
Click a class to see the actual source code with:
- 🟢 Green = line was executed
- 🔴 Red = line was not executed
- 🟡 Yellow = branch partially covered

This helps identify:
- Untested code paths
- Areas needing more test coverage
- Dead code that's never executed

## Tips for Debugging Failed Tests

1. **Check the Summary First**: See which tests failed
2. **Read the Failure Message**: Often tells you exactly what's wrong
3. **Download HTML Report**: For detailed investigation
4. **Check Coverage**: See if the failed code is adequately tested
5. **Run Locally**: `./gradlew test --tests "ClassName.testName"`

## Running the Same Reports Locally

```bash
# Run tests with coverage
./gradlew test jacocoTestReport

# Open test report
open build/reports/tests/test/index.html

# Open coverage report
open build/reports/jacoco/test/html/index.html
```

The local reports look identical to the CI artifacts!
