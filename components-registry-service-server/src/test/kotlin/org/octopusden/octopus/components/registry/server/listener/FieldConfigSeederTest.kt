package org.octopusden.octopus.components.registry.server.listener

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.entity.RegistryConfigEntity
import org.octopusden.octopus.components.registry.server.repository.RegistryConfigRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import java.nio.file.Paths

/**
 * Asserts the UI-swift-sloth field-config seeder: on application startup,
 * `component.groupId.defaultValue` is populated from the first entry of
 * `ConfigHelper.supportedGroupIds()`.
 *
 * `application-common.yml` sets `components-registry.supportedGroupIds:
 * org.octopusden.octopus,io.bcomponent,org.example`, so the first prefix is
 * `org.octopusden.octopus`. (`org.example` was added by the validation
 * follow-up so existing test fixtures using `org.example.test` still pass
 * the new server-side `validateGroupKeyPrefix` check; the seeder still
 * picks the FIRST entry, which is unchanged.)
 *
 * The seeder fires on `ApplicationReadyEvent`, so by the time @SpringBootTest
 * wires up the context the field-config row exists.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(60)
@Tag("integration")
class FieldConfigSeederTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var registryConfigRepository: RegistryConfigRepository

    @Autowired
    private lateinit var seeder: FieldConfigSeeder

    init {
        val testResourcesPath =
            Paths.get(FieldConfigSeederTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @Suppress("UNCHECKED_CAST")
    private fun readDefaultValue(): String? {
        val entity = registryConfigRepository.findById("field-config").orElse(null) ?: return null
        val root = entity.value as? Map<String, Any?> ?: return null
        val component = root["component"] as? Map<String, Any?> ?: return null
        val groupId = component["groupId"] as? Map<String, Any?> ?: return null
        return groupId["defaultValue"] as? String
    }

    /**
     * Reset the `field-config` row before every test so the three cases see
     * a deterministic starting state regardless of JUnit method order. The
     * seeder is idempotent; re-invoking it after the reset re-establishes
     * the `org.octopusden.octopus` baseline that `seedsDefaultValueOnStartup`
     * asserts against. The two `preserves*` cases then mutate the row from
     * a known starting point.
     */
    @BeforeEach
    fun resetFieldConfigToSeededState() {
        val existing = registryConfigRepository.findById("field-config").orElse(null)
        if (existing != null) {
            registryConfigRepository.delete(existing)
            registryConfigRepository.flush()
        }
        seeder.seedComponentGroupIdDefault()
    }

    @Test
    @DisplayName("ApplicationReadyEvent seeds component.groupId.defaultValue from first supportedGroupIds()")
    fun seedsDefaultValueOnStartup() {
        val seeded = readDefaultValue()
        // The first value of `supportedGroupIds: org.octopusden.octopus,io.bcomponent`
        // in application-common.yml is `org.octopusden.octopus`.
        assert(seeded == "org.octopusden.octopus") {
            "expected seed value 'org.octopusden.octopus'; got '$seeded'"
        }
    }

    @Test
    @DisplayName("Seeder is idempotent — admin-overridden defaultValue is preserved on re-seed")
    fun preservesExistingValue() {
        // Simulate an admin (or a prior seeder run) having set a different value.
        // Re-invoking the seeder must NOT clobber it — admins must be able to
        // override the seed without it being reset on every restart.
        val overrideValue = "com.example.admin.override"
        val entity =
            registryConfigRepository.findById("field-config")
                .orElse(RegistryConfigEntity(key = "field-config"))
        entity.value =
            mapOf(
                "component" to mapOf(
                    "groupId" to mapOf("defaultValue" to overrideValue),
                ),
            )
        registryConfigRepository.save(entity)

        seeder.seedComponentGroupIdDefault()

        assert(readDefaultValue() == overrideValue) {
            "expected admin-set value '$overrideValue' to be preserved; got '${readDefaultValue()}'"
        }
    }

    @Test
    @DisplayName("Seeder preserves an admin-set defaultValue = null (key present with null value, treated as intentional opt-out)")
    fun preservesExplicitNullDefaultValue() {
        // Copilot review note (#298): the previous idempotency check was
        // `if (groupIdField[KEY_DEFAULT_VALUE] != null) return`, which
        // treated `defaultValue: null` as "missing" and silently re-seeded
        // it. But an admin who explicitly set `null` may have done so to
        // disable the Portal's auto-suggest default. Switching the check
        // to key-presence (`containsKey`) preserves that intent.
        @Suppress("UNCHECKED_CAST")
        val nullDefault =
            mapOf<String, Any?>(
                "component" to mapOf<String, Any?>(
                    "groupId" to mapOf<String, Any?>("defaultValue" to null),
                ),
            )
        val entity =
            registryConfigRepository.findById("field-config")
                .orElse(RegistryConfigEntity(key = "field-config"))
        entity.value = nullDefault
        registryConfigRepository.save(entity)

        seeder.seedComponentGroupIdDefault()

        // The seeded key still resolves to null — NOT to the
        // `org.octopusden.octopus` first-prefix that the seeder would
        // produce on a blank DB.
        assert(readDefaultValue() == null) {
            "expected admin's explicit null defaultValue to be preserved; got '${readDefaultValue()}'"
        }
        // And the key must still be present in the blob.
        val reloaded = registryConfigRepository.findById("field-config").orElse(null)!!
        @Suppress("UNCHECKED_CAST")
        val groupId =
            ((reloaded.value as Map<String, Any?>)["component"] as Map<String, Any?>)["groupId"]
                as Map<String, Any?>
        assert(groupId.containsKey("defaultValue")) {
            "expected 'defaultValue' key to remain present after seed; got $groupId"
        }
    }

    @Test
    @DisplayName("Seeder bails out on a structurally-incompatible existing blob (non-Map root) without clobbering it")
    fun preservesIncompatibleBlob() {
        // Admin tooling (PUT /admin/config/field-config) accepts ANY JSON
        // value, so an existing blob could be an array, a scalar, etc. The
        // seeder must NOT silently replace such data with a fresh Map — it
        // should detect the shape mismatch and leave the row untouched.
        // We exercise this via the `describeIncompatibleShape` helper, which
        // is the same check the seeder itself runs at startup.
        val incompatibleList = listOf("not", "a", "map")
        val msg = seeder.describeIncompatibleShape(incompatibleList)
        assert(msg != null && msg.contains("root is")) {
            "expected describeIncompatibleShape() to flag a list root; got '$msg'"
        }

        // Same for a Map whose `component` section is itself a non-Map.
        val msgInnerSection = seeder.describeIncompatibleShape(mapOf("component" to listOf("a", "b")))
        assert(msgInnerSection != null && msgInnerSection.contains("'component' is")) {
            "expected describeIncompatibleShape() to flag a non-Map `component` section; got '$msgInnerSection'"
        }

        // Same for component.groupId being a non-Map (a scalar admin set
        // directly, for instance).
        val msgInnerField =
            seeder.describeIncompatibleShape(mapOf("component" to mapOf("groupId" to "scalar-value")))
        assert(msgInnerField != null && msgInnerField.contains("'component.groupId' is")) {
            "expected describeIncompatibleShape() to flag a non-Map groupId field; got '$msgInnerField'"
        }

        // A well-shaped blob must return null.
        assert(seeder.describeIncompatibleShape(mapOf("component" to mapOf("groupId" to mapOf("defaultValue" to "x")))) == null) {
            "expected a well-shaped Map to pass describeIncompatibleShape()"
        }
        assert(seeder.describeIncompatibleShape(emptyMap<String, Any?>()) == null) {
            "expected an empty Map to pass describeIncompatibleShape() (nothing to merge into)"
        }
    }

    @Test
    @DisplayName("Seeder preserves unrelated sections in the field-config blob")
    fun preservesUnrelatedSections() {
        // Mutate the blob to include unrelated admin-edited entries; the seeder
        // must merge the component.groupId.defaultValue (if missing) without
        // dropping anything else.
        val entity =
            registryConfigRepository.findById("field-config")
                .orElse(RegistryConfigEntity(key = "field-config"))
        entity.value =
            mapOf(
                "component" to mapOf(
                    // No groupId entry here — seeder should add it.
                    "displayName" to mapOf("visibility" to "editable"),
                ),
                "build" to mapOf(
                    "javaVersion" to mapOf("visibility" to "readonly"),
                ),
            )
        registryConfigRepository.save(entity)

        seeder.seedComponentGroupIdDefault()

        val reloaded = registryConfigRepository.findById("field-config").orElse(null)!!
        val root = reloaded.value
        @Suppress("UNCHECKED_CAST")
        val component = root["component"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val build = root["build"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val displayName = component["displayName"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val groupId = component["groupId"] as Map<String, Any?>

        assert(displayName["visibility"] == "editable") {
            "expected unrelated component.displayName.visibility preserved"
        }
        @Suppress("UNCHECKED_CAST")
        val javaVersion = build["javaVersion"] as Map<String, Any?>
        assert(javaVersion["visibility"] == "readonly") {
            "expected unrelated build.javaVersion.visibility preserved"
        }
        assert(groupId["defaultValue"] == "org.octopusden.octopus") {
            "expected component.groupId.defaultValue seeded into existing blob; got ${groupId["defaultValue"]}"
        }
    }
}
