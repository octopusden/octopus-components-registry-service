package org.octopusden.octopus.components.registry.server.mapper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentGroupRole
import org.octopusden.octopus.components.registry.server.dto.v4.ConfigurationRowType
import org.octopusden.octopus.components.registry.server.entity.ComponentArtifactIdEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentDocLinkEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentGroupEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentLabelEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentTeamcityProjectEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionMavenArtifactEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionSecurityGroupEntity
import org.octopusden.octopus.components.registry.server.entity.VcsSettingsEntryEntity
import java.util.UUID

/**
 * Phase 6 — `ComponentEntity.toDetailResponse()` rewritten against the v2
 * entity graph and v4 DTOs.
 *
 * Scenarios covered:
 *  - Top-level scalar propagation (componentKey → name, all new v2 fields)
 *  - systemCode → system: String? (single-value, post ui-swift-sloth-system-single)
 *  - labelJunctions  → labels:  Set<String>
 *  - configurations list: BASE / SCALAR_OVERRIDE / MARKER rowType discriminator
 *  - group: AGGREGATOR vs MEMBER role based on groupKey == componentKey
 *  - docs sorted by sortOrder
 *  - teamcityProjects sorted by sortOrder with URL computation
 *  - artifactIds and securityGroups mapping
 *  - parentComponentName from parentComponent?.componentKey
 */
class ComponentDetailMapperTest {

    // -----------------------------------------------------------------------
    // Fixture builders
    // -----------------------------------------------------------------------

    private fun minimalComponent(key: String = "alpha"): ComponentEntity =
        ComponentEntity(
            id = UUID.randomUUID(),
            componentKey = key,
        )

    private fun baseConfigFor(component: ComponentEntity): ComponentConfigurationEntity =
        ComponentConfigurationEntity(
            id = UUID.randomUUID(),
            component = component,
            versionRange = "(,0),[0,)",
            overriddenAttribute = null, // BASE row
            rowType = "BASE",
        )

    // -----------------------------------------------------------------------
    // Top-level scalar projection
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("componentKey → name in wire DTO")
    fun componentKey_mapsToName() {
        val component = minimalComponent("my-service")
        assertEquals("my-service", component.toDetailResponse().name)
    }

    @Test
    @DisplayName("default entity → minimal scalars null/empty, archived=false")
    fun defaultEntity_minimalProjection() {
        val component = minimalComponent()
        val response = component.toDetailResponse()

        assertEquals(component.id, response.id)
        assertNull(response.displayName)
        assertNull(response.componentOwner)
        assertNull(response.productType)
        assertNull(response.system)
        assertNull(response.clientCode)
        assertEquals(false, response.archived)
        assertNull(response.solution)
        assertNull(response.parentComponentName)
        assertTrue(response.releaseManager.isEmpty())
        assertTrue(response.securityChampion.isEmpty())
        assertNull(response.copyright)
        assertNull(response.releasesInDefaultBranch)
        assertTrue(response.labels.isEmpty())
        assertNull(response.jiraDisplayName)
        assertNull(response.jiraHotfixVersionFormat)
        assertNull(response.vcsExternalRegistry)
        assertNull(response.distributionExplicit)
        assertNull(response.distributionExternal)
        assertNull(response.group)
        assertTrue(response.docs.isEmpty())
        assertTrue(response.artifactIds.isEmpty())
        assertTrue(response.securityGroups.isEmpty())
        assertTrue(response.teamcityProjects.isEmpty())
        assertTrue(response.configurations.isEmpty())
    }

    @Test
    @DisplayName("all v2 scalar fields propagate to detail response")
    fun allScalarFields_propagate() {
        val component = minimalComponent().also {
            it.displayName = "Alpha Service"
            it.componentOwner = "owner@example.com"
            it.productType = "PRODUCT"
            it.clientCode = "CLNT"
            it.archived = true
            it.solution = true
            it.replaceReleaseManagerUsernames(listOf("rm-user"))
            it.replaceSecurityChampionUsernames(listOf("sc-user"))
            it.copyright = "(c) 2026 Acme"
            it.releasesInDefaultBranch = true
            it.jiraDisplayName = "Alpha Jira"
            it.jiraHotfixVersionFormat = "\$major.\$minor.x"
            it.vcsExternalRegistry = "ssh://external.git"
            it.distributionExplicit = true
            it.distributionExternal = false
        }
        val response = component.toDetailResponse()

        assertEquals("Alpha Service", response.displayName)
        assertEquals("owner@example.com", response.componentOwner)
        assertEquals("PRODUCT", response.productType)
        assertEquals("CLNT", response.clientCode)
        assertEquals(true, response.archived)
        assertEquals(true, response.solution)
        assertEquals(listOf("rm-user"), response.releaseManager)
        assertEquals(listOf("sc-user"), response.securityChampion)
        assertEquals("(c) 2026 Acme", response.copyright)
        assertEquals(true, response.releasesInDefaultBranch)
        assertEquals("Alpha Jira", response.jiraDisplayName)
        assertEquals("\$major.\$minor.x", response.jiraHotfixVersionFormat)
        assertEquals("ssh://external.git", response.vcsExternalRegistry)
        assertEquals(true, response.distributionExplicit)
        assertEquals(false, response.distributionExternal)
    }

    @Test
    @DisplayName("SYS-044: multi-value releaseManager / securityChampion map to ordered lists (sortOrder preserved)")
    fun `SYS-044 multi-value people map to ordered lists with sortOrder preserved`() {
        val component = minimalComponent().also {
            // Insert out of sortOrder to prove the mapper sorts, not heap order.
            it.replaceReleaseManagerUsernames(listOf("alice", "bob", "carol"))
            it.releaseManagers.reverse()
            it.replaceSecurityChampionUsernames(listOf("dave", "erin"))
        }
        val response = component.toDetailResponse()

        assertEquals(listOf("alice", "bob", "carol"), response.releaseManager)
        assertEquals(listOf("dave", "erin"), response.securityChampion)
    }

    @Test
    @DisplayName("releasesInDefaultBranch=false stays distinct from null")
    fun releasesInDefaultBranch_explicitFalse() {
        val component = minimalComponent().also { it.releasesInDefaultBranch = false }
        assertEquals(false, component.toDetailResponse().releasesInDefaultBranch)
    }

    @Test
    @DisplayName("parentComponent → parentComponentName is parent's componentKey")
    fun parentComponent_mapsToParentComponentName() {
        val parent = minimalComponent("parent-svc")
        val child = minimalComponent("child-svc").also { it.parentComponent = parent }
        assertEquals("parent-svc", child.toDetailResponse().parentComponentName)
    }

    @Test
    @DisplayName("canBeParent propagates to detail response (default false)")
    fun canBeParent_propagates() {
        assertEquals(false, minimalComponent().toDetailResponse().canBeParent)
        val parent = minimalComponent().also { it.canBeParent = true }
        assertEquals(true, parent.toDetailResponse().canBeParent)
    }

    // -----------------------------------------------------------------------
    // systemCode → system: String? (single-value)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("entity systemCode → single-value `system` field in detail response")
    fun systemCode_mapsToScalarSystem() {
        val component = minimalComponent().also { it.systemCode = "UNIX" }
        assertEquals("UNIX", component.toDetailResponse().system)
    }

    @Test
    @DisplayName("null systemCode → system is null in detail response")
    fun nullSystemCode_mapsToNull() {
        val component = minimalComponent()
        assertNull(component.toDetailResponse().system)
    }

    // -----------------------------------------------------------------------
    // labelJunctions → labels: Set<String>
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("labelJunctions → labels set in detail response")
    fun labelJunctions_mapsToLabelsSet() {
        val component = minimalComponent()
        val id = component.id!!
        component.labelJunctions.add(ComponentLabelEntity(componentId = id, labelCode = "backend"))
        component.labelJunctions.add(ComponentLabelEntity(componentId = id, labelCode = "internal"))

        assertEquals(setOf("backend", "internal"), component.toDetailResponse().labels)
    }

    // -----------------------------------------------------------------------
    // configurations — rowType discriminator
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("BASE config row → rowType=BASE with all aspects emitted")
    fun baseConfigRow_rowTypeBase_allAspectsEmitted() {
        val component = minimalComponent()
        val cfg = baseConfigFor(component).also {
            it.buildSystem = "GRADLE"
            it.jiraProjectKey = "PROJ"
            it.escrowProvidedDependencies = "dep1"
        }
        component.configurations.add(cfg)

        val response = component.toDetailResponse()
        assertEquals(1, response.configurations.size)

        val cr = response.configurations[0]
        assertEquals(ConfigurationRowType.BASE, cr.rowType)
        assertNull(cr.overriddenAttribute)
        assertEquals("GRADLE", cr.build?.buildSystem)
        assertEquals("PROJ", cr.jira?.projectKey)
        assertEquals("dep1", cr.escrow?.providedDependencies)
    }

    @Test
    @DisplayName("SCALAR_OVERRIDE row build.javaVersion → rowType=SCALAR_OVERRIDE, only build aspect emitted")
    fun scalarOverride_buildJavaVersion_onlyBuildAspectEmitted() {
        val component = minimalComponent()
        val cfg = ComponentConfigurationEntity(
            id = UUID.randomUUID(),
            component = component,
            versionRange = "[2,3)",
            overriddenAttribute = "build.javaVersion",
            rowType = "SCALAR_OVERRIDE",
            javaVersion = "21",
        )
        component.configurations.add(cfg)

        val cr = component.toDetailResponse().configurations[0]
        assertEquals(ConfigurationRowType.SCALAR_OVERRIDE, cr.rowType)
        assertEquals("build.javaVersion", cr.overriddenAttribute)
        assertEquals("21", cr.build?.javaVersion)
        assertNull(cr.escrow)
        assertNull(cr.jira)
        assertTrue(cr.vcsEntries.isEmpty())
    }

    @Test
    @DisplayName("SCALAR_OVERRIDE row escrow.generation → only escrow aspect emitted")
    fun scalarOverride_escrowGeneration_onlyEscrowAspectEmitted() {
        val component = minimalComponent()
        val cfg = ComponentConfigurationEntity(
            id = UUID.randomUUID(),
            component = component,
            versionRange = "[3,4)",
            overriddenAttribute = "escrow.generation",
            rowType = "SCALAR_OVERRIDE",
            escrowGeneration = "G2",
        )
        component.configurations.add(cfg)

        val cr = component.toDetailResponse().configurations[0]
        assertEquals(ConfigurationRowType.SCALAR_OVERRIDE, cr.rowType)
        assertNull(cr.build)
        assertEquals("G2", cr.escrow?.generation)
        assertNull(cr.jira)
    }

    @Test
    @DisplayName("SCALAR_OVERRIDE row jira.projectKey → only jira aspect emitted")
    fun scalarOverride_jiraProjectKey_onlyJiraAspectEmitted() {
        val component = minimalComponent()
        val cfg = ComponentConfigurationEntity(
            id = UUID.randomUUID(),
            component = component,
            versionRange = "[4,5)",
            overriddenAttribute = "jira.projectKey",
            rowType = "SCALAR_OVERRIDE",
            jiraProjectKey = "HOTFIX",
        )
        component.configurations.add(cfg)

        val cr = component.toDetailResponse().configurations[0]
        assertEquals(ConfigurationRowType.SCALAR_OVERRIDE, cr.rowType)
        assertNull(cr.build)
        assertNull(cr.escrow)
        assertEquals("HOTFIX", cr.jira?.projectKey)
    }

    @Test
    @DisplayName("MARKER row vcs.settings → rowType=MARKER, vcsEntries populated, distributions empty")
    fun markerRow_vcsSettings_vcsEntriesPopulated() {
        val component = minimalComponent()
        val cfg = ComponentConfigurationEntity(
            id = UUID.randomUUID(),
            component = component,
            versionRange = "[5,6)",
            overriddenAttribute = MarkerAttributes.VCS_SETTINGS,
            rowType = "MARKER",
        )
        val vcsEntry = VcsSettingsEntryEntity(
            id = UUID.randomUUID(),
            componentConfiguration = cfg,
            name = "main",
            vcsPath = "org/repo",
            sortOrder = 0,
        )
        cfg.vcsEntries.add(vcsEntry)
        component.configurations.add(cfg)

        val cr = component.toDetailResponse().configurations[0]
        assertEquals(ConfigurationRowType.MARKER, cr.rowType)
        assertNull(cr.build)
        assertNull(cr.escrow)
        assertNull(cr.jira)
        assertEquals(1, cr.vcsEntries.size)
        assertEquals("org/repo", cr.vcsEntries[0].vcsPath)
        assertTrue(cr.mavenArtifacts.isEmpty())
    }

    @Test
    @DisplayName("MARKER row distribution.maven → mavenArtifacts populated, vcs and other families empty")
    fun markerRow_distributionMaven_mavenArtifactsPopulated() {
        val component = minimalComponent()
        val cfg = ComponentConfigurationEntity(
            id = UUID.randomUUID(),
            component = component,
            versionRange = "[6,7)",
            overriddenAttribute = MarkerAttributes.DISTRIBUTION_MAVEN,
            rowType = "MARKER",
        )
        val maven = DistributionMavenArtifactEntity(
            id = UUID.randomUUID(),
            componentConfiguration = cfg,
            groupPattern = "org.example",
            artifactPattern = "service",
            sortOrder = 0,
        )
        cfg.mavenArtifacts.add(maven)
        component.configurations.add(cfg)

        val cr = component.toDetailResponse().configurations[0]
        assertEquals(ConfigurationRowType.MARKER, cr.rowType)
        assertEquals(1, cr.mavenArtifacts.size)
        assertEquals("org.example", cr.mavenArtifacts[0].groupPattern)
        assertTrue(cr.vcsEntries.isEmpty())
        assertTrue(cr.dockerImages.isEmpty())
    }

    @Test
    @DisplayName("mixed configurations list: BASE + SCALAR_OVERRIDE + MARKER all returned")
    fun mixedConfigurations_allReturnedInList() {
        val component = minimalComponent()
        val base = baseConfigFor(component)
        val scalar = ComponentConfigurationEntity(
            id = UUID.randomUUID(),
            component = component,
            versionRange = "[2,3)",
            overriddenAttribute = "build.javaVersion",
            rowType = "SCALAR_OVERRIDE",
            javaVersion = "21",
        )
        val marker = ComponentConfigurationEntity(
            id = UUID.randomUUID(),
            component = component,
            versionRange = "[3,4)",
            overriddenAttribute = MarkerAttributes.VCS_SETTINGS,
            rowType = "MARKER",
        )
        component.configurations.addAll(listOf(base, scalar, marker))

        val configs = component.toDetailResponse().configurations
        assertEquals(3, configs.size)
        val rowTypes = configs.map { it.rowType }.toSet()
        assertTrue(rowTypes.contains(ConfigurationRowType.BASE))
        assertTrue(rowTypes.contains(ConfigurationRowType.SCALAR_OVERRIDE))
        assertTrue(rowTypes.contains(ConfigurationRowType.MARKER))
    }

    // -----------------------------------------------------------------------
    // group — AGGREGATOR vs MEMBER
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("componentKey == groupKey → AGGREGATOR role in group response")
    fun group_aggregatorRole_whenKeyMatchesGroupKey() {
        val component = minimalComponent("platform")
        val group = ComponentGroupEntity(id = UUID.randomUUID(), groupKey = "platform")
        component.componentGroup = group

        val response = component.toDetailResponse()
        assertEquals("platform", response.group?.groupKey)
        assertEquals(ComponentGroupRole.AGGREGATOR, response.group?.role)
        assertEquals(false, response.group?.isFake)
    }

    @Test
    @DisplayName("componentKey != groupKey → MEMBER role in group response")
    fun group_memberRole_whenKeyDiffersFromGroupKey() {
        val component = minimalComponent("alpha")
        val group = ComponentGroupEntity(id = UUID.randomUUID(), groupKey = "platform", isFake = true)
        component.componentGroup = group

        val response = component.toDetailResponse()
        assertEquals(ComponentGroupRole.MEMBER, response.group?.role)
        assertEquals(true, response.group?.isFake)
    }

    @Test
    @DisplayName("no group → group field is null")
    fun group_null_whenNoGroup() {
        assertNull(minimalComponent().toDetailResponse().group)
    }

    // -----------------------------------------------------------------------
    // docs — sorted by sortOrder
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("docs sorted by sortOrder ascending")
    fun docs_sortedBySortOrder() {
        val component = minimalComponent()
        component.docLinks.add(
            ComponentDocLinkEntity(
                id = UUID.randomUUID(), component = component, docComponentKey = "docs-b", sortOrder = 2,
            ),
        )
        component.docLinks.add(
            ComponentDocLinkEntity(
                id = UUID.randomUUID(), component = component, docComponentKey = "docs-a", sortOrder = 1,
            ),
        )

        val docs = component.toDetailResponse().docs
        assertEquals(listOf("docs-a", "docs-b"), docs.map { it.docComponentKey })
    }

    @Test
    @DisplayName("docLink majorVersion preserved in response")
    fun docLink_majorVersion_preserved() {
        val component = minimalComponent()
        component.docLinks.add(
            ComponentDocLinkEntity(
                id = UUID.randomUUID(), component = component,
                docComponentKey = "portal-docs", majorVersion = "3", sortOrder = 0,
            ),
        )

        val doc = component.toDetailResponse().docs[0]
        assertEquals("portal-docs", doc.docComponentKey)
        assertEquals("3", doc.majorVersion)
    }

    // -----------------------------------------------------------------------
    // teamcityProjects — URL computation
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("no TC projects → teamcityProjects list is empty")
    fun teamcityProjects_empty_whenNone() {
        assertTrue(minimalComponent().toDetailResponse().teamcityProjects.isEmpty())
    }

    @Test
    @DisplayName("TC project with base URL → projectUrl computed correctly")
    fun teamcityProject_urlComposed_whenBaseUrlProvided() {
        val component = minimalComponent()
        component.teamcityProjects.add(
            ComponentTeamcityProjectEntity(
                id = UUID.randomUUID(),
                component = component,
                projectId = "MyProject_Alpha",
                sortOrder = 0,
            ),
        )

        val tc = component.toDetailResponse(teamcityBaseUrl = "https://tc.example.com").teamcityProjects[0]
        assertEquals("MyProject_Alpha", tc.projectId)
        assertEquals("https://tc.example.com/project/MyProject_Alpha", tc.projectUrl)
    }

    @Test
    @DisplayName("TC project with trailing-slash base URL → projectUrl has no double slash")
    fun teamcityProject_trailingSlashBaseUrl_noDoubleSlash() {
        val component = minimalComponent()
        component.teamcityProjects.add(
            ComponentTeamcityProjectEntity(
                id = UUID.randomUUID(), component = component, projectId = "Proj", sortOrder = 0,
            ),
        )

        val tc = component.toDetailResponse(teamcityBaseUrl = "https://tc.example.com/").teamcityProjects[0]
        assertEquals("https://tc.example.com/project/Proj", tc.projectUrl)
    }

    @Test
    @DisplayName("TC project with blank base URL → projectUrl is null")
    fun teamcityProject_urlNull_whenBaseUrlBlank() {
        val component = minimalComponent()
        component.teamcityProjects.add(
            ComponentTeamcityProjectEntity(
                id = UUID.randomUUID(), component = component, projectId = "MyProject_Alpha", sortOrder = 0,
            ),
        )

        val tc = component.toDetailResponse(teamcityBaseUrl = "").teamcityProjects[0]
        assertNull(tc.projectUrl)
    }

    @Test
    @DisplayName("TC projects sorted by sortOrder ascending")
    fun teamcityProjects_sortedBySortOrder() {
        val component = minimalComponent()
        component.teamcityProjects.add(
            ComponentTeamcityProjectEntity(
                id = UUID.randomUUID(), component = component, projectId = "Proj_B", sortOrder = 2,
            ),
        )
        component.teamcityProjects.add(
            ComponentTeamcityProjectEntity(
                id = UUID.randomUUID(), component = component, projectId = "Proj_A", sortOrder = 1,
            ),
        )

        val projects = component.toDetailResponse(teamcityBaseUrl = "https://tc.example.com").teamcityProjects
        assertEquals(listOf("Proj_A", "Proj_B"), projects.map { it.projectId })
    }

    // -----------------------------------------------------------------------
    // artifactIds and securityGroups
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("artifactIds mapped to ArtifactIdResponse")
    fun artifactIds_mapped() {
        val component = minimalComponent()
        component.artifactIds.add(
            ComponentArtifactIdEntity(
                id = UUID.randomUUID(), component = component,
                groupPattern = "org.example", artifactPattern = "svc",
            ),
        )

        val ids = component.toDetailResponse().artifactIds
        assertEquals(1, ids.size)
        assertEquals("org.example", ids[0].groupPattern)
        assertEquals("svc", ids[0].artifactPattern)
    }

    @Test
    @DisplayName("securityGroups mapped to SecurityGroupResponse")
    fun securityGroups_mapped() {
        val component = minimalComponent()
        component.securityGroups.add(
            DistributionSecurityGroupEntity(
                id = UUID.randomUUID(), component = component,
                groupType = "read", groupName = "devs",
            ),
        )

        val groups = component.toDetailResponse().securityGroups
        assertEquals(1, groups.size)
        assertEquals("read", groups[0].groupType)
        assertEquals("devs", groups[0].groupName)
    }
}
