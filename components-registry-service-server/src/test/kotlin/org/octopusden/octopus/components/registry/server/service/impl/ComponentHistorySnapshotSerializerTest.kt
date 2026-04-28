package org.octopusden.octopus.components.registry.server.service.impl

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.octopusden.octopus.escrow.BuildSystem
import org.octopusden.octopus.escrow.configuration.model.EscrowModule
import org.octopusden.octopus.escrow.configuration.model.EscrowModuleConfig

class ComponentHistorySnapshotSerializerTest {
    private val serializer = ComponentHistorySnapshotSerializer()
    private val json = ObjectMapper()

    @Test
    fun `identical modules serialize byte-equal`() {
        val a = module("svc", "1.0", BuildSystem.MAVEN, archived = false)
        val b = module("svc", "1.0", BuildSystem.MAVEN, archived = false)
        assertEquals(bytes(serializer.serialize(a)), bytes(serializer.serialize(b)))
    }

    @Test
    fun `different archived produces different snapshot`() {
        val a = module("svc", "1.0", BuildSystem.MAVEN, archived = false)
        val b = module("svc", "1.0", BuildSystem.MAVEN, archived = true)
        assertNotEquals(bytes(serializer.serialize(a)), bytes(serializer.serialize(b)))
    }

    @Test
    fun `moduleName round-trips`() {
        val a = module("my-service", "1.0", BuildSystem.GRADLE, archived = false)
        val snapshot = serializer.serialize(a)
        assertEquals("my-service", snapshot["moduleName"])
    }

    @Test
    fun `labels are sorted for deterministic output`() {
        val a = module("svc", "1.0", BuildSystem.MAVEN, archived = false, labels = linkedSetOf("z", "a", "m"))
        val b = module("svc", "1.0", BuildSystem.MAVEN, archived = false, labels = linkedSetOf("m", "a", "z"))
        assertEquals(bytes(serializer.serialize(a)), bytes(serializer.serialize(b)))
    }

    private fun bytes(snapshot: Map<String, Any?>): String = json.writeValueAsString(snapshot)

    @Suppress("LongParameterList")
    private fun module(
        name: String,
        versionRange: String,
        buildSystem: BuildSystem,
        archived: Boolean,
        labels: Set<String>? = null,
    ): EscrowModule {
        val config = EscrowModuleConfig()
        setField(config, "buildSystem", buildSystem)
        setField(config, "artifactIdPattern", "artifact")
        setField(config, "groupIdPattern", "group")
        setField(config, "versionRange", versionRange)
        config.archived = archived // public setter exists
        if (labels != null) {
            setField(config, "labels", labels)
        }
        return EscrowModule().apply {
            moduleName = name
            moduleConfigurations = mutableListOf(config)
        }
    }

    private fun setField(
        target: Any,
        name: String,
        value: Any?,
    ) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }
}
