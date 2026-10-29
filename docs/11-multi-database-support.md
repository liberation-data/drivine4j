## Multi-Database Support

```kotlin
@Configuration
class MultiDbConfig {
    @Bean
    fun dataSourceMap(): DataSourceMap {
        return DataSourceMap(mapOf(
            "analytics" to ConnectionProperties(
                type = DatabaseType.NEO4J,
                host = "analytics.neo4j.com",
                databaseName = "analytics"
            ),
            "users" to ConnectionProperties(
                type = DatabaseType.NEO4J,
                host = "users.neo4j.com",
                databaseName = "users"
            )
        ))
    }
}

@Component
class AnalyticsRepository @Autowired constructor(
    @Qualifier("analytics") val manager: PersistenceManager
) { /* ... */ }

@Component
class UserRepository @Autowired constructor(
    @Qualifier("users") val manager: PersistenceManager
) { /* ... */ }
```

An object manager for a named database comes from the factory:

```kotlin
@Bean
fun analyticsObjects(factory: GraphObjectManagerFactory): StatelessGraphObjectManager = factory.stateless("analytics")
```

