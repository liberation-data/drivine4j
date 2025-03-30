package drivine.query

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.GenericBeanDefinition
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import java.nio.charset.StandardCharsets

@Configuration
class QueryBeanFactory : ApplicationContextAware {

    private lateinit var beanFactory: ConfigurableListableBeanFactory

    override fun setApplicationContext(applicationContext: ApplicationContext) {
        this.beanFactory = applicationContext.autowireCapableBeanFactory as ConfigurableListableBeanFactory
        registerQueryBeans(beanFactory)
    }

    private fun registerQueryBeans(beanFactory: ConfigurableListableBeanFactory) {
        val resolver = PathMatchingResourcePatternResolver()
        val resources = resolver.getResources("classpath:queries/*.cypher")

        resources.forEach { resource ->
            val queryName = resource.filename!!.removeSuffix(".cypher")
            val queryText = resource.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }

            val beanDefinition = GenericBeanDefinition().apply {
                setBeanClass(String::class.java)
                setInstanceSupplier { queryText }
            }

            (beanFactory as BeanDefinitionRegistry).registerBeanDefinition(queryName, beanDefinition)
        }
    }
}
