# Store layout migration fixtures

`layout1-0.7.0.sqlite` is a SQLite graph (`drivine`) written by drivine4j 0.7.0, the last release of store layout 1, for
`StoreLayoutMigrationSqliteTest`. `src/migrationFixture070/` is the program that wrote it, a build of its own against
the 0.7.0 release from Maven Central; the Postgres and Oracle tests (`StoreLayoutMigrationStoreTest`) run it to write
their graphs into the test's own store. To write the file again:

    ./gradlew migrationFixture070
    src/migrationFixture070/build/install/fixture070/bin/fixture070 sqlite <path of the file> drivine
