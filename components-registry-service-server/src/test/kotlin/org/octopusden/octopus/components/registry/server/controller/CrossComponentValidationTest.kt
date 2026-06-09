package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.support.adminJwt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths
import java.util.UUID

/**
 * Stage-4 cross-component integrity checks restored from the old
 * `EscrowConfigValidator` composite rules. Collisions with OTHER components are
 * 409 (`CrossComponentConflictException`); malformed payloads are 400.
 *
 * Each test seeds its own freshly-named components — it does NOT extend the
 * global `TestComponents` / `Defaults` fixtures (regression-guard rule).
 * `supportedGroupIds` in `application-common.yml` is
 * `org.octopusden.octopus,io.bcomponent,org.example`, so maven groups use those
 * prefixes except where a bad-prefix 400 is the assertion under test.
 */
@Tag("integration")
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(180)
class CrossComponentValidationTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(CrossComponentValidationTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private fun sfx() = UUID.randomUUID().toString().take(8)

    /**
     * Restored person-field validation (foundation PR) makes `componentOwner`
     * required non-blank on every successful v4 create. These fixtures don't
     * care about the owner, so inject a default once when the body omits it —
     * mirrors the passthrough-helper approach used by the foundation's fixture
     * commit. Bodies that explicitly set componentOwner (none here today) are
     * left untouched.
     */
    private fun withOwner(body: String): String =
        if (body.contains("\"componentOwner\"")) {
            body
        } else {
            body.replaceFirst("{", """{"componentOwner":"owner1",""")
        }

    private fun postCreate(body: String): ResultActions =
        mvc.perform(
            post("/rest/api/4/components")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(withOwner(body)),
        )

    private fun patchComponent(id: String, body: String): ResultActions =
        mvc.perform(
            patch("/rest/api/4/components/$id")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )

    /** Create a component, asserting 2xx, and return its id. */
    private fun createOk(body: String): String {
        val resp = postCreate(body).andExpect(status().is2xxSuccessful).andReturn().response.contentAsString
        return objectMapper.readTree(resp)["id"].asText()
    }

    // ───────────────────────── #24/#25 maven groupId:artifactId collision ─────

    @Test
    @DisplayName("CREATE: duplicate groupId:artifactId in an overlapping range with another component → 409")
    fun create_duplicateMavenArtifact_overlappingRange_conflict() {
        val s = sfx()
        val group = "org.octopusden.octopus"
        val artifact = "shared-artifact-$s"
        createOk(
            """{"name":"xcc-maven-a-$s",""" +
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"},""" +
                """"mavenArtifacts":[{"groupPattern":"$group","artifactPattern":"$artifact"}]}}""",
        )
        // Second component, same GAV — BASE rows both cover the universal range
        // (,0),[0,) so they intersect → conflict.
        postCreate(
            """{"name":"xcc-maven-b-$s",""" +
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"},""" +
                """"mavenArtifacts":[{"groupPattern":"$group","artifactPattern":"$artifact"}]}}""",
        ).andExpect(status().isConflict)
    }

    @Test
    @DisplayName("CREATE: same groupId:artifactId on a DIFFERENT artifactId → no conflict (2xx)")
    fun create_differentArtifact_noConflict() {
        val s = sfx()
        val group = "org.octopusden.octopus"
        createOk(
            """{"name":"xcc-maven-c-$s",""" +
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"},""" +
                """"mavenArtifacts":[{"groupPattern":"$group","artifactPattern":"artifact-c-$s"}]}}""",
        )
        postCreate(
            """{"name":"xcc-maven-d-$s",""" +
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"},""" +
                """"mavenArtifacts":[{"groupPattern":"$group","artifactPattern":"artifact-d-$s"}]}}""",
        ).andExpect(status().is2xxSuccessful)
    }

    @Test
    @DisplayName("CREATE: wildcard artifactId pattern '*' overlaps with specific artifactId → 409")
    fun create_wildcardArtifact_overlappingRange_conflict() {
        val s = sfx()
        val group = "org.octopusden.octopus.$s"
        createOk(
            """{"name":"xcc-maven-wildcard-a-$s",""" +
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"},""" +
                """"mavenArtifacts":[{"groupPattern":"$group","artifactPattern":"*"}]}}""",
        )
        postCreate(
            """{"name":"xcc-maven-wildcard-b-$s",""" +
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"},""" +
                """"mavenArtifacts":[{"groupPattern":"$group","artifactPattern":"specific-artifact-$s"}]}}""",
        ).andExpect(status().isConflict)
    }

    @Test
    @DisplayName("CREATE: regex artifactId pattern overlaps with specific matching artifactId → 409")
    fun create_regexArtifact_overlappingRange_conflict() {
        val s = sfx()
        val group = "org.octopusden.octopus.$s"
        createOk(
            """{"name":"xcc-maven-regex-a-$s",""" +
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"},""" +
                """"mavenArtifacts":[{"groupPattern":"$group","artifactPattern":"match-.*"}]}}""",
        )
        postCreate(
            """{"name":"xcc-maven-regex-b-$s",""" +
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"},""" +
                """"mavenArtifacts":[{"groupPattern":"$group","artifactPattern":"match-artifact-$s"}]}}""",
        ).andExpect(status().isConflict)
    }

    @Test
    @DisplayName("CREATE: CSV groupPattern overlap → 409")
    fun create_csvGroup_overlappingRange_conflict() {
        val s = sfx()
        val groupA = "org.octopusden.octopus.$s,io.bcomponent.$s"
        val groupB = "io.bcomponent.$s"
        val artifact = "csv-artifact-$s"
        createOk(
            """{"name":"xcc-maven-csv-a-$s",""" +
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"},""" +
                """"mavenArtifacts":[{"groupPattern":"$groupA","artifactPattern":"$artifact"}]}}""",
        )
        postCreate(
            """{"name":"xcc-maven-csv-b-$s",""" +
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"},""" +
                """"mavenArtifacts":[{"groupPattern":"$groupB","artifactPattern":"$artifact"}]}}""",
        ).andExpect(status().isConflict)
    }

    @Test
    @DisplayName("CREATE: whitespace and pipe-separated groupPattern overlap → 409")
    fun create_pipeSeparatedGroup_overlappingRange_conflict() {
        val s = sfx()
        val sharedGroup = "io.bcomponent.$s"
        val artifact = "pipe-artifact-$s"
        createOk(
            """{"name":"xcc-maven-pipe-a-$s",""" +
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"},""" +
                """"mavenArtifacts":[{"groupPattern":"org.octopusden.octopus.$s | $sharedGroup","artifactPattern":"$artifact"}]}}""",
        )
        postCreate(
            """{"name":"xcc-maven-pipe-b-$s",""" +
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"},""" +
                """"mavenArtifacts":[{"groupPattern":"org.example.$s, $sharedGroup","artifactPattern":"$artifact"}]}}""",
        ).andExpect(status().isConflict)
    }

    // ───────────────────────── #26 jira projectKey + versionPrefix uniqueness ──

    @Test
    @DisplayName("CREATE: same jira projectKey+versionPrefix on another non-archived component → 409")
    fun create_duplicateJiraProjectKeyPrefix_conflict() {
        val s = sfx()
        val projectKey = "XCCJIRA${s.take(4).uppercase()}"
        createOk(
            """{"name":"xcc-jira-a-$s",""" +
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"},""" +
                """"jira":{"projectKey":"$projectKey","versionPrefix":"prefixA"}}}""",
        )
        postCreate(
            """{"name":"xcc-jira-b-$s",""" +
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"},""" +
                """"jira":{"projectKey":"$projectKey","versionPrefix":"prefixA"}}}""",
        ).andExpect(status().isConflict)
    }

    @Test
    @DisplayName("CREATE: same jira projectKey but DIFFERENT versionPrefix → no conflict (2xx)")
    fun create_sameProjectKeyDifferentPrefix_noConflict() {
        val s = sfx()
        val projectKey = "XCCJIRA${s.take(4).uppercase()}"
        createOk(
            """{"name":"xcc-jira-c-$s",""" +
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"},""" +
                """"jira":{"projectKey":"$projectKey","versionPrefix":"alpha"}}}""",
        )
        postCreate(
            """{"name":"xcc-jira-d-$s",""" +
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"},""" +
                """"jira":{"projectKey":"$projectKey","versionPrefix":"beta"}}}""",
        ).andExpect(status().is2xxSuccessful)
    }

    @Test
    @DisplayName("CREATE: same jira projectKey+prefix but the rival is ARCHIVED → no conflict (2xx)")
    fun create_duplicateJira_rivalArchived_noConflict() {
        val s = sfx()
        val projectKey = "XCCJIRA${s.take(4).uppercase()}"
        createOk(
            """{"name":"xcc-jira-arch-$s","archived":true,""" +
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"},""" +
                """"jira":{"projectKey":"$projectKey","versionPrefix":"gamma"}}}""",
        )
        postCreate(
            """{"name":"xcc-jira-live-$s",""" +
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"},""" +
                """"jira":{"projectKey":"$projectKey","versionPrefix":"gamma"}}}""",
        ).andExpect(status().is2xxSuccessful)
    }

    @Test
    @DisplayName("CREATE: same jira projectKey, BOTH null versionPrefix, both non-archived → 409 (review #5)")
    fun create_duplicateJiraProjectKey_bothNullPrefix_conflict() {
        val s = sfx()
        val projectKey = "XCCNULL${s.take(4).uppercase()}"
        // Neither component sets jira.versionPrefix, so both land in the
        // (projectKey, NULL) bucket. This exercises the null-safe branch of
        // findOtherNonArchivedComponentKeysByJiraProjectKeyAndVersionPrefix
        // (a plain `version_prefix = :prefix` would miss NULL = NULL).
        createOk(
            """{"name":"xcc-jira-null-a-$s",""" +
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"},""" +
                """"jira":{"projectKey":"$projectKey"}}}""",
        )
        postCreate(
            """{"name":"xcc-jira-null-b-$s",""" +
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"},""" +
                """"jira":{"projectKey":"$projectKey"}}}""",
        ).andExpect(status().isConflict)
    }

    // ───────────────────────── #29 docker image-name global uniqueness ─────────

    @Test
    @DisplayName("CREATE: duplicate docker image name across components → 409")
    fun create_duplicateDockerImage_conflict() {
        val s = sfx()
        val image = "registry.example/img-$s"
        createOk(
            """{"name":"xcc-docker-a-$s",""" +
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"},""" +
                """"dockerImages":[{"imageName":"$image"}]}}""",
        )
        postCreate(
            """{"name":"xcc-docker-b-$s",""" +
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"},""" +
                """"dockerImages":[{"imageName":"$image"}]}}""",
        ).andExpect(status().isConflict)
    }

    @Test
    @DisplayName("PATCH: setting a docker image already owned by another component → 409")
    fun patch_duplicateDockerImage_conflict() {
        val s = sfx()
        val image = "registry.example/patch-img-$s"
        createOk(
            """{"name":"xcc-docker-existing-$s",""" +
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"},""" +
                """"dockerImages":[{"imageName":"$image"}]}}""",
        )
        val targetResp =
            postCreate(
                """{"name":"xcc-docker-target-$s",""" +
                    """"baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}""",
            ).andExpect(status().is2xxSuccessful).andReturn().response.contentAsString
        val target = objectMapper.readTree(targetResp)
        val id = target["id"].asText()
        val version = target["version"].asLong()
        patchComponent(
            id,
            """{"version":$version,"baseConfiguration":{"dockerImages":[{"imageName":"$image"}]}}""",
        ).andExpect(status().isConflict)
    }

    // ───────────────────────── #6 explicit-external ≥1 coordinate (400) ────────

    @Test
    @DisplayName("CREATE: explicit+external with NO distribution coordinate → 400")
    fun create_explicitExternal_noCoordinate_badRequest() {
        val s = sfx()
        // RM/SC supplied so the explicit+external person-field gate passes and the
        // 400 under test is the ≥1-coordinate rule (#6), not a missing-releaseManager
        // rejection (person validation runs first on create).
        postCreate(
            """{"name":"xcc-ext-nocoord-$s","distributionExplicit":true,"distributionExternal":true,""" +
                """"releaseManager":["rm1"],"securityChampion":["sc1"],""" +
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}""",
        ).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("CREATE: explicit+external WITHOUT a componentDisplayName → 400 (displayName)")
    fun create_explicitExternal_noDisplayName_badRequest() {
        val s = sfx()
        // RM/SC + a coordinate supplied so the person-field gate and the ≥1-coordinate rule
        // both pass; the 400 under test is the EE displayName requirement (mirrors
        // EscrowConfigValidator.validateExplicitExternalComponent). displayName itself stays
        // nullable for non-EE components — this requirement is gated on explicit && external.
        postCreate(
            """{"name":"xcc-ext-nodisp-$s","distributionExplicit":true,"distributionExternal":true,""" +
                """"releaseManager":["rm1"],"securityChampion":["sc1"],""" +
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"},""" +
                """"dockerImages":[{"imageName":"registry.example/nodisp-$s"}]}}""",
        ).andExpect(status().isBadRequest)
            .andExpect(
                jsonPath("$.errorMessage").value(org.hamcrest.Matchers.containsString("displayName")),
            )
    }

    @Test
    @DisplayName("CREATE: explicit+external WITH a docker coordinate → 2xx")
    fun create_explicitExternal_withCoordinate_ok() {
        val s = sfx()
        // An explicit+external component also trips the foundation's person-field
        // gate (releaseManager + securityChampion required, non-blank, ^\w+$) and the
        // displayName requirement (componentDisplayName must be set for explicit+external,
        // mirroring EscrowConfigValidator). Supply all three so the only thing under test
        // here is the ≥1-coordinate rule (docker image present → 2xx).
        postCreate(
            """{"name":"xcc-ext-coord-$s","displayName":"Ext Coord $s",""" +
                """"distributionExplicit":true,"distributionExternal":true,""" +
                """"releaseManager":["rm1"],"securityChampion":["sc1"],""" +
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"},""" +
                """"dockerImages":[{"imageName":"registry.example/ext-$s"}]}}""",
        ).andExpect(status().is2xxSuccessful)
    }

    @Test
    @DisplayName("PATCH: clearing displayName on an explicit+external component → 400 (displayName)")
    fun patch_clearDisplayName_onExplicitExternal_badRequest() {
        val s = sfx()
        // displayName-only PATCH that clears the value: must still be rejected for an
        // explicit+external component (validateRequiredDisplayName runs on every update,
        // not only on a cross-component-relevant change — otherwise a clear would slip past).
        val resp =
            postCreate(
                """{"name":"xcc-ext-clear-$s","displayName":"Ext Clear $s",""" +
                    """"distributionExplicit":true,"distributionExternal":true,""" +
                    """"releaseManager":["rm1"],"securityChampion":["sc1"],""" +
                    """"baseConfiguration":{"build":{"buildSystem":"MAVEN"},""" +
                    """"dockerImages":[{"imageName":"registry.example/clear-$s"}]}}""",
            ).andExpect(status().is2xxSuccessful).andReturn().response.contentAsString
        val node = objectMapper.readTree(resp)
        val id = node["id"].asText()
        val version = node["version"].asLong()

        patchComponent(id, """{"version":$version,"displayName":""}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorMessage").value(org.hamcrest.Matchers.containsString("displayName")))
    }

    // ───────────────────────── #10 groupId supported prefix (400) ──────────────

    @Test
    @DisplayName("CREATE: maven groupId with an unsupported prefix → 400")
    fun create_unsupportedGroupIdPrefix_badRequest() {
        val s = sfx()
        postCreate(
            """{"name":"xcc-badgroup-$s",""" +
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"},""" +
                """"mavenArtifacts":[{"groupPattern":"com.unsupported.vendor","artifactPattern":"x-$s"}]}}""",
        ).andExpect(status().isBadRequest)
    }

    // ───────────────────────── #28 archived ≠ explicit-external (400) ──────────

    @Test
    @DisplayName("CREATE: archived component that is explicit+external → 400")
    fun create_archivedExplicitExternal_badRequest() {
        val s = sfx()
        // RM/SC supplied so the explicit+external person-field gate passes and the
        // 400 under test is the archived-vs-explicit-external rule (#28), not a
        // missing-releaseManager rejection (person validation runs first on create).
        postCreate(
            """{"name":"xcc-arch-ext-$s","archived":true,""" +
                """"distributionExplicit":true,"distributionExternal":true,""" +
                """"releaseManager":["rm1"],"securityChampion":["sc1"],""" +
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"},""" +
                """"dockerImages":[{"imageName":"registry.example/arch-$s"}]}}""",
        ).andExpect(status().isBadRequest)
    }

    // ───────────────────────── #20 doc-component existence (400) ───────────────

    @Test
    @DisplayName("CREATE: docs[] referencing a non-existent doc component → 400")
    fun create_docComponentMissing_badRequest() {
        val s = sfx()
        postCreate(
            """{"name":"xcc-doc-missing-$s",""" +
                """"docs":[{"docComponentKey":"no-such-doc-$s"}],""" +
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}""",
        ).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("CREATE: docs[] referencing an EXISTING doc component → 2xx")
    fun create_docComponentExists_ok() {
        val s = sfx()
        val docKey = "xcc-doc-target-$s"
        createOk("""{"name":"$docKey","baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}""")
        postCreate(
            """{"name":"xcc-doc-ref-$s",""" +
                """"docs":[{"docComponentKey":"$docKey"}],""" +
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}""",
        ).andExpect(status().is2xxSuccessful)
    }

    @Test
    @DisplayName("CREATE: docs[] referencing the component's OWN key (self-documenting) → 2xx (review #6)")
    fun create_docComponentSelfReference_ok() {
        val s = sfx()
        val ownKey = "xcc-doc-self-$s"
        // A component may legitimately document itself. The own key does not
        // exist in the DB until the flush on create, so the doc-existence check
        // must run POST-flush (review #6) for this to be accepted as 2xx rather
        // than a false 400.
        postCreate(
            """{"name":"$ownKey",""" +
                """"docs":[{"docComponentKey":"$ownKey"}],""" +
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}""",
        ).andExpect(status().is2xxSuccessful)
    }

    @Test
    @DisplayName("PATCH: rename-only validates docs[] references left on the old key → 400")
    fun patch_renameOnly_invalidatesOldSelfDocReference() {
        val s = sfx()
        val oldKey = "xcc-doc-rename-old-$s"
        val id =
            createOk(
                """{"name":"$oldKey",""" +
                    """"docs":[{"docComponentKey":"$oldKey"}],""" +
                    """"baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}""",
            )

        patchComponent(id, """{"version":0,"name":"xcc-doc-rename-new-$s"}""")
            .andExpect(status().isBadRequest)
    }

    // ─── #2 field-override (MARKER) write also runs the 409 checks (review #2) ───

    @Test
    @DisplayName("createFieldOverride that sets a GAV another component already claims → 409 (review #2)")
    fun createFieldOverride_duplicateMavenArtifact_conflict() {
        val s = sfx()
        val group = "org.octopusden.octopus"
        val artifact = "fo-shared-artifact-$s"
        // Component A owns the GAV on its BASE row (universal range).
        createOk(
            """{"name":"xcc-fo-owner-$s",""" +
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"},""" +
                """"mavenArtifacts":[{"groupPattern":"$group","artifactPattern":"$artifact"}]}}""",
        )
        // Component B starts WITHOUT the GAV (valid create).
        val bResp =
            postCreate(
                """{"name":"xcc-fo-claimant-$s",""" +
                    """"baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}""",
            ).andExpect(status().is2xxSuccessful).andReturn().response.contentAsString
        val bId = objectMapper.readTree(bResp)["id"].asText()
        // A MARKER field-override on B that introduces the SAME GAV in an
        // overlapping range must hit the cross-component 409 check (review #2:
        // field-override writes previously bypassed it).
        mvc.perform(
            post("/rest/api/4/components/$bId/field-overrides")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"versionRange":"[1.0,2.0)","overriddenAttribute":"distribution.maven",""" +
                        """"markerChildren":{"mavenArtifacts":[{"groupPattern":"$group","artifactPattern":"$artifact"}]}}""",
                ),
        ).andExpect(status().isConflict)
    }

    @Test
    @DisplayName("createFieldOverride that sets a docker image another component already claims → 409 (review #2)")
    fun createFieldOverride_duplicateDockerImage_conflict() {
        val s = sfx()
        val image = "registry.example/fo-img-$s"
        createOk(
            """{"name":"xcc-fo-docker-owner-$s",""" +
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"},""" +
                """"dockerImages":[{"imageName":"$image"}]}}""",
        )
        val bResp =
            postCreate(
                """{"name":"xcc-fo-docker-claimant-$s",""" +
                    """"baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}""",
            ).andExpect(status().is2xxSuccessful).andReturn().response.contentAsString
        val bId = objectMapper.readTree(bResp)["id"].asText()
        mvc.perform(
            post("/rest/api/4/components/$bId/field-overrides")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"versionRange":"[1.0,2.0)","overriddenAttribute":"distribution.docker",""" +
                        """"markerChildren":{"dockerImages":[{"imageName":"$image"}]}}""",
                ),
        ).andExpect(status().isConflict)
    }
}
