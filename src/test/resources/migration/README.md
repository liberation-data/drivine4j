# Store layout migration fixtures

`layout1-0.7.0.sqlite` is a SQLite graph (`drivine`) written by drivine4j 0.7.0, the last release of store layout 1, for
`StoreLayoutMigrationSqliteTest`. `fixture070/` is the program that wrote it, built against the 0.7.0 release from Maven
Central. To write it again, copy `fixture070/` out of the checkout, copy this repository's `gradlew` and `gradle/wrapper`
beside it, and run `./gradlew run --args=<path of the file>`.
