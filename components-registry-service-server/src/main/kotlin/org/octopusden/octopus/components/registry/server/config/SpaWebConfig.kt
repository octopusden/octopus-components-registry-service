package org.octopusden.octopus.components.registry.server.config

import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.resource.PathResourceResolver

@Configuration
class SpaWebConfig : WebMvcConfigurer {
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        registry
            .addResourceHandler("/**")
            .addResourceLocations("classpath:/static/")
            .resourceChain(true)
            .addResolver(
                object : PathResourceResolver() {
                    override fun getResource(
                        resourcePath: String,
                        location: Resource,
                    ): Resource? {
                        val requestedResource = location.createRelative(resourcePath)
                        if (requestedResource.exists() && requestedResource.isReadable) {
                            return requestedResource
                        }
                        // Skip API calls — they should 404 naturally
                        if (resourcePath.startsWith("rest/") || resourcePath.startsWith("actuator/")) {
                            return null
                        }
                        return ClassPathResource("/static/index.html")
                    }
                },
            )
    }
}
