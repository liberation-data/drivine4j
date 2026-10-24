package org.drivine.studio

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.drivine.manager.GraphObjectManagerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.ImportAware
import org.springframework.core.type.AnnotationMetadata
import org.springframework.util.ClassUtils

/**
 * Turns the studio on: read-only endpoints under `/drivine/studio/api` that list the application's
 * `@GraphView` and `@NodeFragment` classes and run `loadAll` against them.
 *
 * The studio shows whatever the datasource holds to whoever can reach the endpoints, and it has no
 * login of its own. Enable it in development, or put it behind the application's security.
 * `drivine.studio.enabled=false` turns it off without removing the annotation.
 *
 * @param basePackages where to look for views and fragments; by default the package of the annotated
 * class. `drivine.studio.base-packages` overrides both.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Import(DrivineStudioConfiguration::class)
annotation class EnableDrivineStudio(val basePackages: Array<String> = [])

@Configuration
@ConditionalOnProperty(name = ["drivine.studio.enabled"], havingValue = "true", matchIfMissing = true)
class DrivineStudioConfiguration : ImportAware {

    private var declaredPackages: List<String> = emptyList()

    override fun setImportMetadata(importMetadata: AnnotationMetadata) {
        @Suppress("UNCHECKED_CAST")
        val named = importMetadata.getAnnotationAttributes(EnableDrivineStudio::class.java.name)
            ?.get("basePackages") as? Array<String>
        declaredPackages = named?.toList()?.ifEmpty { null }
            ?: listOf(ClassUtils.getPackageName(importMetadata.className))
    }

    @Bean
    fun drivineStudioCatalog(
        @Value("\${drivine.studio.base-packages:}") configuredPackages: List<String>,
    ): StudioCatalog = StudioCatalog.scan(configuredPackages.filter { it.isNotBlank() }.ifEmpty { declaredPackages })

    @Bean
    fun drivineStudioService(
        catalog: StudioCatalog,
        factory: GraphObjectManagerFactory,
        @Value("\${drivine.studio.datasource:\${drivine.default-datasource:default}}") datasource: String,
        @Value("\${drivine.studio.default-limit:50}") defaultLimit: Int,
        @Value("\${drivine.studio.max-limit:500}") maxLimit: Int,
    ): StudioService = StudioService(
        catalog,
        StudioQueryTranslator(catalog, jacksonObjectMapper().findAndRegisterModules(), defaultLimit, maxLimit),
        factory.stateless(datasource),
    )

    @Configuration
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    class Endpoints {
        @Bean
        fun drivineStudioController(service: StudioService) = StudioController(service)
    }
}
