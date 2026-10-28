## Installation

### Spring Boot Starter

Drivine4j works hand in hand with Spring. For a Spring Boot application, add the starter: it brings the core library,
Drivine's transaction manager and the auto-configuration (see [Configuration](04-quick-start.md#1-configuration)). `@Transactional` and
`TransactionTemplate` run Drivine's transactions, alongside your other Spring transactions.

```kotlin
dependencies {
    implementation("org.drivine:drivine4j-spring-boot-starter:0.4.0")
}
```

Spring without Boot takes `drivine4j-spring` instead: the core library and `DrivineTransactionManager`, Drivine's
`PlatformTransactionManager`, for `@Transactional` and `TransactionTemplate`, with no auto-configuration. The starter
is built on it.

### Core Library

The core library depends only on what it uses: the drivers, Jackson and HikariCP. The Spring artifacts above bring it,
and it can be used on its own too, with another framework or none (see [Other frameworks, or none](04-quick-start.md#4-other-frameworks-or-none)).

#### Gradle (Kotlin DSL)
```kotlin
dependencies {
    implementation("org.drivine:drivine4j:0.4.0")
}
```

#### Gradle (Groovy)
```groovy
dependencies {
    implementation 'org.drivine:drivine4j:0.4.0'
}
```

#### Maven
```xml
<dependency>
    <groupId>org.drivine</groupId>
    <artifactId>drivine4j</artifactId>
    <version>0.4.0</version>
</dependency>
```

| Artifact | Holds | Depends on |
|---|---|---|
| `drivine4j-spring-boot-starter` | the auto-configuration, `@EnableDrivine`, `@EnableDrivinePropertiesConfig`, `@EnableDrivineTestConfig` | `drivine4j-spring`, Spring Boot |
| `drivine4j-spring` | `DrivineTransactionManager`, for `@Transactional` and `TransactionTemplate`; `FalkorDbCleanupListener` | `drivine4j`, Spring Framework (`spring-tx`) |
| `drivine4j` | the engines, the managers, the query DSL, schema management, `Transactions` and `Drivine` | the drivers, Jackson, HikariCP |
| `drivine4j-coroutines` | `transactionSuspending { }`, a transaction block for Kotlin coroutines: see [Transactions in coroutines](07-core-features.md#transactions-in-coroutines-drivine4j-coroutines) | `drivine4j`, `kotlinx-coroutines-core` |

### Code Generation (For the Type-Safe Query DSL)

If you want to use the object manager with the type-safe query DSL, you need to add the code generation processor.

> **Note for Java Projects:** Both Java and Kotlin are fully supported at runtime. The code generator (KSP) produces Kotlin DSL extensions, but a Java-friendly query builder API is also available. Define your `@GraphView` and `@NodeFragment` classes in either language. See the [Java Interoperability](14-java-interoperability.md#java-interoperability) section for details.

#### Gradle (Kotlin DSL)

```kotlin
plugins {
    id("com.google.devtools.ksp") version "2.2.20-2.0.4"
    kotlin("jvm") version "2.2.0"
}

kotlin {
    compilerOptions {
        // Required for context parameters DSL
        freeCompilerArgs.addAll("-Xcontext-parameters")
    }
}

dependencies {
    implementation("org.drivine:drivine4j:0.4.0")
    ksp("org.drivine:drivine4j-codegen:0.4.0")
}
```

#### Maven

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.jetbrains.kotlin</groupId>
            <artifactId>kotlin-maven-plugin</artifactId>
            <version>2.2.0</version>
            <configuration>
                <compilerPlugins>
                    <compilerPlugin>ksp</compilerPlugin>
                </compilerPlugins>
                <args>
                    <!-- Required for context parameters DSL -->
                    <arg>-Xcontext-parameters</arg>
                </args>
            </configuration>
            <dependencies>
                <!-- KSP extension for Maven -->
                <dependency>
                    <groupId>com.dyescape</groupId>
                    <artifactId>kotlin-maven-symbol-processing</artifactId>
                    <version>1.6</version>
                </dependency>

                <!-- Drivine code generator -->
                <dependency>
                    <groupId>org.drivine</groupId>
                    <artifactId>drivine4j-codegen</artifactId>
                    <version>0.4.0</version>
                </dependency>
            </dependencies>
        </plugin>
    </plugins>
</build>
```

**Note:** Maven support for KSP uses the third-party [kotlin-maven-symbol-processing](https://github.com/Dyescape/kotlin-maven-symbol-processing) extension.

#### The model file

The code generator also writes the module's **model file**, `.drivine/model.json`, beside its build file. It lists the module's fragments, relationship fragments and views, and how each is stored: labels, the stored name of each property, subtypes, bags, indexes, and what each view's relationships load. Drivine Studio reads it to browse the model and query the data, without the application running. The format is [drivine-cypher-spec's `model/`](https://github.com/liberation-data/drivine-cypher-spec/tree/main/model), one for all three Drivine libraries.

Keep the file in source control, as you would a lock file: Studio can then read a repository's model straight from GitHub, and a change to the model shows in review as a change to the file. The build writes it again whenever the model changes and leaves it alone otherwise. To have CI fail when the committed file is out of date or missing, check for a change after the build:

```sh
./gradlew build
test -z "$(git status --porcelain -- ':(glob)**/.drivine/model.json')"
```

When a fragment, relationship fragment or view leaves the code, its entry stays in the file as it last was, marked `"removed": true`: the database may still hold nodes written under it, and Studio greys it out rather than show a node it cannot name. It goes back to current if the class comes back, and stays until you delete the entry from the file, once the database holds nothing of it. A module with nothing left keeps its file, with every entry removed.

The jar carries the same file, as `META-INF/drivine/model.json`. Both code generators write it: the KSP processor, and the Java annotation processor, `drivine4j-codegen-java`, for a Java module. Options change what is written, given to KSP with `ksp { arg(…) }` and to the Java processor with `-A`:

| Option | Effect |
|---|---|
| `drivine.model` | `false` writes no model file. |
| `drivine.model.module` | The module's name in the file. By default, the module directory's name. |
| `drivine.model.file` | An absolute path to write the file to instead. Needed where the generator cannot tell the main sources from the tests': for KSP outside Gradle's layout, as with Maven. The Java processor knows Gradle's layout and Maven's, and writes no file, a named one included, as it compiles the tests. |

```kotlin
ksp {
    arg("drivine.model.module", "orders")
}
```

```groovy
// The Java processor, in Gradle
dependencies {
    annotationProcessor 'org.drivine:drivine4j-codegen-java:0.4.0'
}
tasks.withType(JavaCompile).configureEach {
    options.compilerArgs += ['-Adrivine.model.module=orders']
}
```

