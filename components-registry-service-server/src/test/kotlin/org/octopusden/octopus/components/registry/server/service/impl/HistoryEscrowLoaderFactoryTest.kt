package org.octopusden.octopus.components.registry.server.service.impl

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.streams.asSequence

@SpringBootTest(classes = [ComponentRegistryServiceApplication::class])
@ActiveProfiles("common", "test-db")
class HistoryEscrowLoaderFactoryTest {
    @Autowired
    private lateinit var factory: HistoryEscrowLoaderFactory

    init {
        val testResourcesPath =
            Paths.get(HistoryEscrowLoaderFactoryTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @Test
    fun `builds a loader able to parse a copied fixture directory`(
        @TempDir tmp: Path,
    ) {
        val fixtureSrc = Paths.get(System.getProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR"), "components-registry", "common")
        copyTree(fixtureSrc, tmp)

        val loader = factory.build(tmp)
        val cfg = loader.loadFullConfigurationWithoutValidationForUnknownAttributes(emptyMap())

        assertTrue(cfg.escrowModules.isNotEmpty(), "escrowModules should not be empty")
    }

    private fun copyTree(
        src: Path,
        dst: Path,
    ) {
        Files.walk(src).use { stream ->
            stream.asSequence().forEach { source ->
                val target = dst.resolve(src.relativize(source).toString())
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    companion object {
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
