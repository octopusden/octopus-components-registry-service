package org.octopusden.octopus.components.registry.dsl

import com.fasterxml.jackson.databind.ser.impl.SimpleBeanPropertyFilter
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider
import org.octopusden.octopus.components.registry.api.build.BuildSystem
import org.octopusden.octopus.components.registry.api.build.tools.databases.DatabaseTool
import org.octopusden.octopus.components.registry.api.build.tools.products.ProductTool
import org.octopusden.octopus.components.registry.api.enums.BuildSystemType
import org.octopusden.octopus.components.registry.api.enums.ProductTypes
import org.octopusden.octopus.components.registry.api.enums.VcsTypes
import org.octopusden.octopus.components.registry.api.model.Dependencies
import org.octopusden.octopus.components.registry.dsl.jackson.JacksonFactory
import org.octopusden.octopus.components.registry.dsl.script.ComponentsRegistryScriptRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.IllegalArgumentException
import java.util.logging.Level
import java.util.logging.Logger
import org.octopusden.octopus.components.registry.api.beans.BuildBean
import org.octopusden.octopus.components.registry.api.beans.PTCProductToolBean
import org.octopusden.octopus.components.registry.api.beans.ClassicBuildSystem
import org.octopusden.octopus.components.registry.api.beans.ComponentBean
import org.octopusden.octopus.components.registry.api.beans.PTDDbProductToolBean
import org.octopusden.octopus.components.registry.api.beans.PTDProductToolBean
import org.octopusden.octopus.components.registry.api.beans.EscrowBean
import org.octopusden.octopus.components.registry.api.beans.GradleBuildBean
import org.octopusden.octopus.components.registry.api.beans.PTKProductToolBean
import org.octopusden.octopus.components.registry.api.beans.OdbcToolBean
import org.octopusden.octopus.components.registry.api.beans.OracleDatabaseToolBean
import org.octopusden.octopus.components.registry.api.beans.SubComponentBean
import org.octopusden.octopus.components.registry.api.beans.VersionedComponentConfigurationBean

private val logger = Logger.getLogger("org.octopusden.octopus.components.registry.dsl.ComponentsRegistryDSL")

val ANY_ARTIFACT = arrayOf(".*")

val GRADLE = BuildSystemType.GRADLE

val GIT = VcsTypes.GIT

val PT_K = ProductTypes.PT_K
val PT_C = ProductTypes.PT_C
val PT_D_DB = ProductTypes.PT_D_DB
val PT_D = ProductTypes.PT_D

open class SubComponentDSL(private val subComponent: SubComponentBean): VersionedComponentDSL(subComponent) {
    fun version(range: String, init: VersionedComponentDSL.() -> Unit) {
        val versionedComponent = clone(subComponent, VersionedComponentConfigurationBean::class.java)
        val versionedComponentDSL = VersionedComponentDSL(versionedComponent!!)
        versionedComponentDSL.init()
        subComponent.versions[range] = versionedComponent
    }
}

data class JiraComponent(var versionPrefix: String, var versionFormat: String)

data class Distribution(var explicit: Boolean, var external: Boolean)

open class ComponentDSL(private val parentComponent: ComponentBean): SubComponentDSL(parentComponent) {
    var productType = parentComponent.productType?.let { ComponentsRegistryScriptRunner.encodeParameters(it) }
        set(value) {
            parentComponent.productType = value?.let { ComponentsRegistryScriptRunner.decodeParameters(it) }
            field = value
        }

    var displayName = parentComponent.displayName
        set(value) {
            parentComponent.displayName = value
            field = value
        }
    fun components(init: ComponentsDSL.() -> Unit) {
        val components =  ComponentsDSL(parentComponent)
        components.init()
    }
}

class ComponentsDSL(private val parentComponent: ComponentBean) {
    fun component(key: String, init: SubComponentDSL.() -> Unit) {
        val subComponent = cloneCommonSections(parentComponent, SubComponentBean::class.java)
        subComponent.name = key
        val subComponentDSL = SubComponentDSL(subComponent)
        subComponentDSL.init()
        checkIntegrity("Component '$key' is already defined") { !parentComponent.subComponents.any { it.key == key} }
        parentComponent.subComponents[key] = subComponent
    }
}

open class VersionedComponentDSL(private val versionedComponent: VersionedComponentConfigurationBean) {
    var groupId = versionedComponent.groupId
        set(value) {
            versionedComponent.groupId = value
            field = value
        }

    var artifactId = versionedComponent.artifactIds
        set(value) {
            versionedComponent.artifactIds = value
            field = value
        }

    fun escrow(init: EscrowDSL.() -> Unit) {
        val escrowDsl = EscrowDSL(versionedComponent.escrow)
        escrowDsl.init()
    }

    fun build(init: BuildDSL.() -> Unit) {
        val buildDSL = BuildDSL()
        buildDSL.init()
        versionedComponent.setBuild(buildDSL.getBuild())
    }

    fun vcsSettings(vcsSettings: VcsSettingsDSL.() -> Unit) {
    }

    fun git(vcsSettings: GitSettingsDSL.() -> Unit) {
    }

    fun mercurial(vcsSettings: GitSettingsDSL.() -> Unit) {

    }

    fun jira(jira: JiraDSL.() -> Unit) {
    }

    fun distribution(distribution: Distribution.() -> Unit) {
    }
}

fun component(componentKey: String, init: ComponentDSL.() -> Unit) {
    val rootComponent = ComponentBean()
    rootComponent.name = componentKey
    val rootComponentDSL = ComponentDSL(rootComponent)
    rootComponentDSL.init()
    ComponentsRegistryScriptRunner.addRootComponent(rootComponent)
}

class GradleDSL(private val gradleBuild: GradleBuildBean) {
    var includeTestConfigurations = gradleBuild.includeTestConfigurations
        set(value) {
            gradleBuild.includeTestConfigurations = value
            field = value
        }

    /**
     * Override inherited included configuration.
     */
    var includeConfigurations: Collection<String> = gradleBuild.includeConfigurations
        set(value) {
            gradleBuild.includeConfigurations = value
            field = value
        }

    /**
     * Override inherited excluded configuration.
     */
    var excludeConfigurations: Collection<String> = gradleBuild.excludeConfigurations
        set(value) {
            gradleBuild.excludeConfigurations = value
            field = value
        }
    /**
     * Add gradle configuration to escrow. This configuration will be added to inherited including configurations.
     */
    fun includeConfiguration(configuration: String) {
        gradleBuild.includeConfigurations += configuration
    }

    /**
     * Remove gradle configuration from escrow. This configuration will be added to inherited excluding configurations.
     */
    fun excludeConfiguration(configuration: String) {
        gradleBuild.excludeConfigurations += configuration
    }
}


class EscrowDSL(private val escrow: EscrowBean) {
    var providedDependencies: Collection<String> = escrow.providedDependencies
        set(value) {
            escrow.providedDependencies.clear()
            escrow.providedDependencies.addAll(value)
            field = escrow.providedDependencies
        }

    var additionalSources: Collection<String> = escrow.additionalSources
        set(value) {
            escrow.additionalSources.clear()
            escrow.additionalSources.addAll(value)
            field = escrow.additionalSources
        }

    var buildTask: String? = escrow.buildTask
        set(value) {
            escrow.buildTask = value
            field = value
        }

    var reusable: Boolean = escrow.isReusable
        set(value) {
            escrow.isReusable = value
            field = value
        }

    fun gradle(dsl: GradleDSL.() -> Unit) {
        val gradleDSL = GradleDSL(escrow.gradle)
        gradleDSL.dsl()
    }

    var diskspace: Any? = escrow.diskSpaceRequirement.orElse(null)
        set(value) {
            when (value) {
                null -> {
                    escrow.setDiskspaceRequirement(value)
                }
                is Number -> {
                    escrow.setDiskspaceRequirement(value.toLong())
                }
                is String -> {
                    var multiplier = 1
                    if (value.endsWith("KB") || value.endsWith("K")) {
                        multiplier = 1024
                    } else if (value.endsWith("MB") || value.endsWith("M")) {
                        multiplier = 1024 * 1024
                    } else if (value.endsWith("GB") || value.endsWith("G")) {
                        multiplier = 1024 * 1024 * 1024
                    }
                    escrow.setDiskspaceRequirement(value.replace(Regex("(KB)|(K)|(MB)|(M)|(GB)|(G)"), "").toLong() * multiplier)
                }
                else -> {
                    throw IllegalArgumentException("Not supported type " + value::class.java)
                }
            }
            field = value
        }
}

class BuildSystemDSL {
    var type: BuildSystemType? = null
    var version: String? = null

    fun gradle() {
        type = BuildSystemType.GRADLE
    }

    internal fun getBuildSystem(): BuildSystem? {
        if (type != null) {
            return ClassicBuildSystem(type!!, version)
        }
        return null
    }
}

class BuildDSL {
    var javaVersion: String? = null
    private var build = BuildBean()
    private var buildSystem: BuildSystem? = null

    fun tools(dsl: ToolsDSL.() -> Unit) {
        val toolsDSL = ToolsDSL()
        toolsDSL.dsl()
        build = toolsDSL.getBuild()
    }

    fun buildSystem(dsl: BuildSystemDSL.() -> Unit) {
        val buildSystemDSL = BuildSystemDSL()
        buildSystemDSL.dsl()
        buildSystem = buildSystemDSL.getBuildSystem()
    }

    fun dependencies(init: DependenciesDSL.() -> Unit) {
        val dependenciesDSL = DependenciesDSL()
        dependenciesDSL.init()
        build.dependencies = dependenciesDSL.toDependencies()
    }

    internal fun getBuild(): BuildBean {
        javaVersion?.also { build.javaVersion = it }
        return build
    }
}

class ToolsDSL {
    private val build = BuildBean()
    fun database(init: DatabaseDSL.() -> Unit) {
        val databaseDSL = DatabaseDSL()
        databaseDSL.init()
        build.tools.addAll(databaseDSL.databaseTools)
    }

    fun product(init: ProductDSL.() -> Unit) {
        val productDSL = ProductDSL()
        productDSL.init()
        build.tools.addAll(productDSL.productTools)
    }

    fun odbc(init: OdbcToolBean.() -> Unit) {
        val odbcToolBean = OdbcToolBean()
        odbcToolBean.init()
        build.tools.add(odbcToolBean)
    }

    internal fun getBuild(): BuildBean {
        //TODO Perform merge with parent
        return build
    }
}

class OdbcDSL {
    var version: String = "12.2"
}

class DatabaseDSL {
    internal val databaseTools = ArrayList<DatabaseTool>()
    fun oracle(init: OracleDSL.() -> Unit) {
        val oracleDSL = OracleDSL()
        oracleDSL.init()
        val oracleDatabaseTool = OracleDatabaseToolBean()
        oracleDatabaseTool.version = oracleDSL.version
        checkIntegrity("More than one oracle database is set") { databaseTools.isEmpty() }
        databaseTools.add(oracleDatabaseTool)
    }
}

class OctopusProductDSL {
    var version: String? = null
}

class OracleDSL {
    var version: String? = null
}

class ProductDSL {
    internal val productTools = ArrayList<ProductTool>()

    fun type(name: String, init: OctopusProductDSL.() -> Unit) {
        val productType = ComponentsRegistryScriptRunner.decodeParameters(name)
        val product = OctopusProductDSL()
        product.init()
        val componentProduct = when(productType) {
            ProductTypes.PT_C -> PTCProductToolBean()
            ProductTypes.PT_K -> PTKProductToolBean()
            ProductTypes.PT_D_DB -> PTDDbProductToolBean()
            ProductTypes.PT_D -> PTDProductToolBean()
        }
        componentProduct.version = product.version
        checkIntegrity("More than one COMPONENT product is set") { !productTools.any { it.type == productType } }
        productTools.add(componentProduct)
    }

}

open class VcsSpecificationDSL {
    lateinit var vcsUrl: String
    var branch: String? = null
    var tag: String? = null
}

open class VcsSettingsDSL: VcsSpecificationDSL {
    constructor() : super()
    constructor(repositoryType: VcsTypes) : super() {
        this.repositoryType = repositoryType
    }
    lateinit var repositoryType: VcsTypes
}

class GitSettingsDSL: VcsSettingsDSL(VcsTypes.GIT)
class MercurialSettingsDSL: VcsSettingsDSL(VcsTypes.MERCURIAL)

class JiraDSL {
    var projectKey: String? = null
    var majorVersionFormat: String? = null
    var releaseVersionFormat: String? = null
    var buildVersionFormat: String? = null
    var technical = false
    fun component(jiraComponent: JiraComponent.()-> Unit) {
    }
}

class DistributionDSL {
    var projectKey: String? = null
    var majorVersionFormat: String? = null
    var releaseVersionFormat: String? = null
    fun component(jiraComponent: JiraComponent.()-> Unit) {
    }
}

class DependenciesDSL {
    var autoUpdate: Boolean = false

    internal fun toDependencies(): Dependencies =
        Dependencies(autoUpdate)

}

class SettingsDSL {
    var param1: String = ""
}

fun throwException(message: String) {
    //TODO Log registry path to problem
    throw RuntimeException(message)
}

fun checkIntegrity(message: String, closure: () -> Boolean) {
    if (!closure()) {
        throwException(message)
    }
}

internal fun <T> clone(source: Any?, clazz: Class<T>): T? {
    if (source == null) {
        return null
    }
    val mapper = JacksonFactory.instance.objectMapper
    val outputStream = ByteArrayOutputStream()
    val componentCloneFilter = SimpleBeanPropertyFilter.serializeAllExcept("subComponents", "versions", "name", "displayName", "productType")
    val filters = SimpleFilterProvider().addFilter("cloneFilter", componentCloneFilter)
    mapper.writer(filters).writeValue(outputStream, source)
    if (logger.isLoggable(Level.FINE)) {
        logger.fine("Clone json for $clazz is ${String(outputStream.toByteArray())}")
    }
    return mapper.readValue(ByteArrayInputStream(outputStream.toByteArray()), clazz)
}

internal fun <T: SubComponentBean> cloneCommonSections(source: T, clazz: Class<T>): T {
    val component = clazz.getDeclaredConstructor().newInstance()
    //Copy all properties which should be inherited, e.g. jira section
    component.setBuild(clone(source.build, BuildBean::class.java))
    return component
}
