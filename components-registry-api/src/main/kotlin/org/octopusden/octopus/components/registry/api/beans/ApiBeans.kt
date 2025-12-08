package org.octopusden.octopus.components.registry.api.beans

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonFilter
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonTypeName
import org.octopusden.octopus.components.registry.api.Component
import org.octopusden.octopus.components.registry.api.SubComponent
import org.octopusden.octopus.components.registry.api.VersionedComponentConfiguration
import org.octopusden.octopus.components.registry.api.build.Build
import org.octopusden.octopus.components.registry.api.build.BuildSystem
import org.octopusden.octopus.components.registry.api.build.tools.BuildTool
import org.octopusden.octopus.components.registry.api.build.tools.databases.DatabaseTool
import org.octopusden.octopus.components.registry.api.build.tools.databases.OracleDatabaseTool
import org.octopusden.octopus.components.registry.api.build.tools.oracle.OdbcTool
import org.octopusden.octopus.components.registry.api.build.tools.products.PTCProductTool
import org.octopusden.octopus.components.registry.api.build.tools.products.PTDDbProductTool
import org.octopusden.octopus.components.registry.api.build.tools.products.PTDProductTool
import org.octopusden.octopus.components.registry.api.build.tools.products.PTKProductTool
import org.octopusden.octopus.components.registry.api.build.tools.products.ProductTool
import org.octopusden.octopus.components.registry.api.distribution.entities.MavenArtifactDistributionEntity
import org.octopusden.octopus.components.registry.api.enums.BuildSystemType
import org.octopusden.octopus.components.registry.api.enums.BuildToolTypes
import org.octopusden.octopus.components.registry.api.enums.DatabaseTypes
import org.octopusden.octopus.components.registry.api.enums.EscrowGenerationMode
import org.octopusden.octopus.components.registry.api.enums.OracleDatabaseEditions
import org.octopusden.octopus.components.registry.api.enums.ProductTypes
import org.octopusden.octopus.components.registry.api.enums.VersionControlSystemType
import org.octopusden.octopus.components.registry.api.escrow.Escrow
import org.octopusden.octopus.components.registry.api.escrow.GradleBuild
import org.octopusden.octopus.components.registry.api.model.Dependencies
import org.octopusden.octopus.components.registry.api.vcs.CommonVersionControlSystem
import org.octopusden.octopus.components.registry.api.vcs.ExternalVersionControlSystem
import org.octopusden.octopus.components.registry.api.vcs.GitVersionControlSystem
import org.octopusden.octopus.components.registry.api.vcs.MultiplyVersionControlSystem
import org.octopusden.octopus.components.registry.api.vcs.VersionControlSystem
import java.util.*
import java.util.regex.Pattern

open class OdbcToolBean:
    OdbcTool {
    private var version: String = "12.2"

    override fun getBuildToolType(): BuildToolTypes = BuildToolTypes.ODBC

    fun setVersion(version: String) {
        this.version  = version
    }

    override fun getVersion(): String = version
}

open class ProductToolBean(private val type: ProductTypes, private var settingsProperty: String):
    ProductTool {
    private var version: String? = null

    override fun getSettingsProperty(): String = settingsProperty

    fun setSettingsProperty(settingsProperty: String) {
        this.settingsProperty = settingsProperty
    }

    fun setVersion(version: String?) {
        this.version  = version
    }

    override fun getVersion(): String? = version
    override fun getType(): ProductTypes = type

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProductToolBean) return false

        if (type != other.type) return false
        if (settingsProperty != other.settingsProperty) return false
        if (version != other.version) return false

        return true
    }

    override fun getBuildToolType(): BuildToolTypes = BuildToolTypes.PRODUCT

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + settingsProperty.hashCode()
        result = 31 * result + (version?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "ProductToolBean(type=$type, settingsProperty='$settingsProperty', version=$version)"
    }

}

open class PTCProductToolBean: ProductToolBean(ProductTypes.PT_C, "uscschema"),
    PTCProductTool
open class PTKProductToolBean: ProductToolBean(ProductTypes.PT_K, "uskschema"),
    PTKProductTool
open class PTDDbProductToolBean: ProductToolBean(ProductTypes.PT_D_DB, "usdschema"),
    PTDDbProductTool
open class PTDProductToolBean: ProductToolBean(ProductTypes.PT_D, "usdschema"),
    PTDProductTool

open class DatabaseToolBean(private val type: DatabaseTypes, private var settingsProperty: String):
    DatabaseTool {
    private var version: String? = null

    override fun getType(): DatabaseTypes = type

    override fun getSettingsProperty(): String = settingsProperty

    override fun getVersion(): String? = version

    fun setVersion(version: String?) {
        Objects.requireNonNull(version, "Version cannot be null")
        this.version  = version
    }

    fun setSettingsProperty(settingsProperty: String) {
        this.settingsProperty = settingsProperty
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DatabaseToolBean) return false

        if (type != other.type) return false
        if (settingsProperty != other.settingsProperty) return false
        if (version != other.version) return false

        return true
    }

    override fun getBuildToolType(): BuildToolTypes = BuildToolTypes.DATABASE

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + settingsProperty.hashCode()
        result = 31 * result + (version?.hashCode() ?: 0)
        return result
    }

}

class OracleDatabaseToolBean: DatabaseToolBean(DatabaseTypes.ORACLE, "db"),
    OracleDatabaseTool {
    private var edition: OracleDatabaseEditions? = null
    override fun getEdition(): OracleDatabaseEditions? = edition
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OracleDatabaseToolBean) return false
        if (!super.equals(other)) return false

        if (edition != other.edition) return false

        return true
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + (edition?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "OracleDatabaseToolBean(edition=$edition)"
    }
}

open class GradleBuildBean: GradleBuild {
    private var includeTestConfigurations = false
    private var includeConfigurations: Collection<String> = ArrayList()
    private var excludeConfigurations: Collection<String> = ArrayList()

    override fun getIncludeTestConfigurations(): Boolean  = includeTestConfigurations
    fun setIncludeTestConfigurations(includeTestConfigurations: Boolean) {
        this.includeTestConfigurations = includeTestConfigurations
    }

    override fun getExcludeConfigurations() = excludeConfigurations

    override fun getIncludeConfigurations() = includeConfigurations


    fun setIncludeConfigurations(includeConfigurations: Collection<String>) {
        this.includeConfigurations = includeConfigurations
    }

    fun setExcludeConfigurations(excludeConfigurations: Collection<String>) {
        this.excludeConfigurations = excludeConfigurations
    }

}

open class EscrowBean: Escrow {
    private var buildTask: String? = null
    private val gradle: GradleBuildBean = GradleBuildBean()
    private val providedDependencies = mutableListOf<String>()
    private var diskSpaceRequirement: Long? = null
    private val additionalSources = ArrayList<String>()
    private var reusable = true
    private var generation: EscrowGenerationMode = EscrowGenerationMode.UNSUPPORTED

    constructor()

    constructor(
        generation: EscrowGenerationMode,
        buildTask: String? = null,
        providedDependencies: Collection<String> = emptyList(),
        diskSpaceRequirement: Long? = null,
        additionalSources: Collection<String> = emptyList(),
        reusable: Boolean = true
    ) {
        this.generation = generation
        this.buildTask = buildTask
        this.providedDependencies.addAll(providedDependencies)
        this.diskSpaceRequirement = diskSpaceRequirement
        this.additionalSources.addAll(additionalSources)
        this.reusable = reusable
    }

    override fun getBuildTask(): String? = this.buildTask

    fun setBuildTask(buildTask: String?) {
        this.buildTask = buildTask
    }

    fun setDiskspaceRequirement(bytes: Long?) {
        this.diskSpaceRequirement = bytes
    }

    override fun getProvidedDependencies(): MutableCollection<String> = providedDependencies

    override fun getDiskSpaceRequirement(): Optional<Long> = Optional.ofNullable(diskSpaceRequirement)

    override fun getGradle(): GradleBuildBean = gradle

    override fun getAdditionalSources(): MutableCollection<String> = additionalSources

    override fun isReusable(): Boolean = reusable

    fun setReusable(reusable: Boolean) {
        this.reusable = reusable
    }

    override fun getGeneration(): EscrowGenerationMode = generation

    fun setGeneration(generation: EscrowGenerationMode) {
        this.generation = generation
    }

    override fun toString(): String {
        return "EscrowBean(buildTask=$buildTask, gradle=$gradle, providedDependencies=$providedDependencies, diskSpaceRequirement=$diskSpaceRequirement, additionalSources=$additionalSources, reusable=$reusable, generation=$generation)"
    }

}

open class BuildBean: Build {
    private var javaVersion: String? = null
    private val buildTools = ArrayList<BuildTool>()
    private var dependencies: Dependencies? = null
    private var buildSystem: BuildSystem? = null

    override fun getTools() = buildTools
    override fun getJavaVersion() = javaVersion
    override fun getDependencies() = dependencies
    override fun getBuildSystem() = buildSystem

    fun setJavaVersion(javaVersion: String?) {
        this.javaVersion = javaVersion
    }

    fun setDependencies(dependencies: Dependencies?) {
        this.dependencies = dependencies
    }

    fun setBuildSystem(buildSystem: BuildSystem?) {
        this.buildSystem = buildSystem
    }
}

open class VersionedComponentConfigurationBean:
    VersionedComponentConfiguration {
    private var build: BuildBean? = null
    private var escrow: EscrowBean = EscrowBean()
    private var artifactIds: Collection<String> = emptyList()
    private var groupId: String? = null
    private var versionControlSystem: VersionControlSystem? = null

    override fun getBuild(): Build? = build
    override fun getEscrow(): EscrowBean = escrow
    override fun getGroupId(): String? = groupId
    override fun getVcs(): VersionControlSystem? = versionControlSystem

    override fun getArtifactIds(): Collection<String> = artifactIds

    fun setEscrow(escrow: EscrowBean) {
        this.escrow = escrow
    }

    fun setBuild(build: BuildBean?) {
        this.build = build
    }

    fun setGroupId(groupId: String?) {
        this.groupId = groupId
    }

    fun setArtifactIds(artifactIds: Collection<String>) {
        this.artifactIds = artifactIds
    }

    fun setVcs(versionControlSystem: VersionControlSystem?) {
        this.versionControlSystem = versionControlSystem
    }
}

@JsonFilter("cloneFilter")
open class SubComponentBean: VersionedComponentConfigurationBean(),
    SubComponent {
    private lateinit var name: String
    private val versions = HashMap<String, VersionedComponentConfigurationBean>()
    override fun getVersions(): MutableMap<String, VersionedComponentConfigurationBean> = versions
    override fun getName(): String = name
    fun setName(name: String) {
        this.name = name
    }
}

@JsonFilter("cloneFilter")
open class ComponentBean: SubComponentBean(),
    Component {
    private var productType: ProductTypes? = null
    private val subComponents = HashMap<String, SubComponentBean>()
    private var displayName: String? = null

    override fun getDisplayName(): String? {
        return displayName
    }

    fun setProductType(productType: ProductTypes?) {
        this.productType = productType
    }

    override fun getProductType(): ProductTypes? = productType


    fun setDisplayName(displayName: String?) {
        this.displayName = displayName
    }

    override fun getSubComponents(): MutableMap<String, SubComponentBean> = subComponents
    override fun toString(): String {
        return "Component(productType=$productType, subComponents=$subComponents, displayName=$displayName)"
    }
}

@JsonTypeName("mavenDistribution")
open class MavenArtifactDistributionEntityBean:
    MavenArtifactDistributionEntity {
    private var gav: String
    private var groupId: String
    private var artifactId: String
    private var classifier: String? = null
    private var extension: String? = null

    @JsonCreator
    constructor(@JsonProperty("gav") gav: String,
                @JsonProperty("groupId") groupId: String,
                @JsonProperty("artifactId") artifactId: String,
                @JsonProperty("classifier") classifier: String?,
                @JsonProperty("extension") extension: String?
    ) {
        this.gav = gav
        this.groupId = groupId
        this.artifactId = artifactId
        this.classifier = classifier
        this.extension = extension
    }

    constructor(gav: String) {
        this.gav = gav
        val parts = gav.split(Pattern.compile(":"), 4)
        groupId = parts[0]
        artifactId = parts[1]
        extension = if (parts.size > 2) parts[2] else null
        classifier = if (parts.size > 3) parts[3] else null
    }

    override fun getGav() = gav
    override fun getGroupId() = groupId
    override fun getArtifactId() = artifactId
    override fun getClassifier() = Optional.ofNullable(classifier)
    override fun getExtension() = Optional.ofNullable(extension)
}

open class VersionControlSystemBean(private val type: VersionControlSystemType):
    VersionControlSystem {
    override fun getType(): VersionControlSystemType = type

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VersionControlSystemBean) return false
        if (type != other.type) return false
        return true
    }

    override fun hashCode(): Int {
        return type.hashCode()
    }
}

class ExternalVersionControlSystemBean: ExternalVersionControlSystem, VersionControlSystemBean(
    VersionControlSystemType.EXTERNAL)

class MultiplyVersionControlSystemBean: MultiplyVersionControlSystem, VersionControlSystemBean(
    VersionControlSystemType.MULTIPLY) {
    private var vcs: Collection<VersionControlSystem> = ArrayList()
    override fun getVersionControlSystems(): Collection<VersionControlSystem> = vcs

    fun setVcs(vcs: Collection<VersionControlSystem>) {
        this.vcs = vcs
    }
}

abstract class CommonVersionControlSystemBean: CommonVersionControlSystem, VersionControlSystemBean {
    private lateinit var url: String
    private lateinit var tag: String
    private lateinit var branch: String

    constructor(type: VersionControlSystemType) : super(type)

    constructor(type: VersionControlSystemType, url: String, tag: String, branch: String) : this(type) {
        this.url = url
        this.tag = tag
        this.branch = branch
    }

    override fun getUrl()= url
    override fun getTag() = tag
    override fun getBranch() = branch

    fun setUrl(url: String) {
        this.url = url
    }

    fun setBranch(branch: String) {
        this.branch = branch
    }

    fun setTag(tag: String) {
        this.tag = tag
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GitVersionControlSystemBean) return false
        if (!super.equals(other)) return false

        if (url != other.url) return false
        if (tag != other.tag) return false
        if (branch != other.branch) return false

        return true
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + url.hashCode()
        result = 31 * result + tag.hashCode()
        result = 31 * result + branch.hashCode()
        return result
    }

    override fun toString(): String {
        return "CommonVersionControlSystemBean(url='$url', tag='$tag', branch='$branch')"
    }
}

class GitVersionControlSystemBean: GitVersionControlSystem, CommonVersionControlSystemBean {
    constructor(): super(VersionControlSystemType.GIT)
    constructor(url: String, tag: String, branch: String) : super(VersionControlSystemType.GIT, url, tag, branch)
    override fun toString(): String {
        return "GitVersionControlSystemBean(url='$url', tag='$tag', branch='$branch')"
    }
}

class ClassicBuildSystem: BuildSystem {
    private val buildSystemType: BuildSystemType
    private var buildSystemVersion: String? = null

    constructor(@JsonProperty("type") buildSystemType: BuildSystemType): this(buildSystemType, null)

    @JsonCreator
    constructor(@JsonProperty("type") buildSystemType: BuildSystemType,
                @JsonProperty("version") buildSystemVersion: String?
    ) {
        this.buildSystemType = buildSystemType
        this.buildSystemVersion = buildSystemVersion
    }

    override fun getType() = buildSystemType

    override fun getVersion() = Optional.ofNullable(buildSystemVersion)

    fun setBuildSystemVersion(buildSystemVersion: String) {
        this.buildSystemVersion = buildSystemVersion
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ClassicBuildSystem) return false

        if (buildSystemType != other.buildSystemType) return false
        if (buildSystemVersion != other.buildSystemVersion) return false

        return true
    }

    override fun hashCode(): Int {
        var result = buildSystemType.hashCode()
        result = 31 * result + (buildSystemVersion?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "ClassicBuildSystem(buildSystemType=$buildSystemType, buildSystemVersion=$buildSystemVersion)"
    }
}