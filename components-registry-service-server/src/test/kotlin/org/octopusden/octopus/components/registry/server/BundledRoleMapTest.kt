package org.octopusden.octopus.components.registry.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource

/**
 * The role map CRS ships in its `application.yml`. Read directly, because the test profiles
 * define their own copies of the editor and admin roles, and those copies win in a running context.
 */
class BundledRoleMapTest {
    private val roles: Map<String, Set<String>> by lazy {
        val sources = YamlPropertySourceLoader().load("application.yml", ClassPathResource("application.yml"))
        Binder(ConfigurationPropertySources.from(sources))
            .bind("octopus-security.roles", Bindable.mapOf(String::class.java, List::class.java))
            .get()
            .mapValues { (_, permissions) -> permissions.map { it.toString() }.toSet() }
    }

    @Test
    @DisplayName("SYS-104: the bundled template creator role carries ACCESS_COMPONENTS, ACCESS_AUDIT and CREATE_FROM_TEMPLATE (Decision 1)")
    fun `SYS-104 bundled template creator role`() {
        assertEquals(
            setOf("ACCESS_COMPONENTS", "ACCESS_AUDIT", "CREATE_FROM_TEMPLATE"),
            roles["ROLE_COMPONENTS_REGISTRY_TEMPLATE_CREATOR"],
        )
    }

    @Test
    @DisplayName("SYS-104: the bundled editor role keeps its permissions, without CREATE_FROM_TEMPLATE (Decision 2)")
    fun `SYS-104 bundled editor role unchanged`() {
        assertEquals(
            setOf("ACCESS_COMPONENTS", "CREATE_COMPONENTS", "ACCESS_AUDIT"),
            roles["ROLE_COMPONENTS_REGISTRY_EDITOR"],
        )
    }

    @Test
    @DisplayName("SYS-104: the bundled admin role keeps its permissions, without CREATE_FROM_TEMPLATE (Decision 2)")
    fun `SYS-104 bundled admin role unchanged`() {
        assertEquals(
            setOf(
                "ACCESS_COMPONENTS",
                "CREATE_COMPONENTS",
                "EDIT_ANY_COMPONENT",
                "ARCHIVE_COMPONENTS",
                "RENAME_COMPONENTS",
                "DELETE_COMPONENTS",
                "IMPORT_DATA",
                "ACCESS_AUDIT",
                "EDIT_METADATA",
            ),
            roles["ROLE_ADMIN"],
        )
    }
}
