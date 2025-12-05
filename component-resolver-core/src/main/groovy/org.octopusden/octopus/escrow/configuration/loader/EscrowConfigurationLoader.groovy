package org.octopusden.octopus.escrow.configuration.loader

import groovy.transform.TypeChecked
import groovy.transform.TypeCheckingMode
import org.apache.commons.lang3.StringUtils
import org.apache.commons.lang3.Validate
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException
import org.apache.maven.artifact.versioning.VersionRange
import org.octopusden.octopus.components.registry.api.Component
import org.octopusden.octopus.components.registry.api.SubComponent
import org.octopusden.octopus.components.registry.api.VersionedComponentConfiguration
import org.octopusden.octopus.components.registry.api.beans.EscrowBean
import org.octopusden.octopus.components.registry.api.enums.EscrowGenerationMode
import org.octopusden.octopus.components.registry.api.model.Dependencies
import org.octopusden.octopus.escrow.BuildSystem
import org.octopusden.octopus.escrow.ModelConfigPostProcessor
import org.octopusden.octopus.escrow.RepositoryType
import org.octopusden.octopus.escrow.configuration.model.DefaultConfigParameters
import org.octopusden.octopus.escrow.configuration.model.EscrowConfiguration
import org.octopusden.octopus.escrow.configuration.model.EscrowModule
import org.octopusden.octopus.escrow.configuration.model.EscrowModuleConfig
import org.octopusden.octopus.escrow.configuration.validation.EscrowConfigValidator
import org.octopusden.octopus.escrow.exceptions.ComponentResolverException
import org.octopusden.octopus.escrow.exceptions.EscrowConfigurationException
import org.octopusden.octopus.escrow.model.BuildParameters
import org.octopusden.octopus.escrow.model.Distribution
import org.octopusden.octopus.components.registry.api.escrow.Escrow

import org.octopusden.octopus.escrow.model.SecurityGroups
import org.octopusden.octopus.escrow.model.VCSSettings
import org.octopusden.octopus.escrow.model.VersionControlSystemRoot
import org.octopusden.octopus.escrow.model.Tool
import org.octopusden.octopus.escrow.resolvers.ComponentHotfixSupportResolver
import org.octopusden.octopus.escrow.resolvers.ReleaseInfoResolver
import org.octopusden.octopus.releng.JiraComponentVersionFormatter
import org.octopusden.octopus.releng.dto.ComponentInfo
import org.octopusden.octopus.releng.dto.ComponentVersion
import org.octopusden.octopus.releng.dto.JiraComponent
import org.octopusden.releng.versions.ComponentVersionFormat
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.VersionRangeFactory

import java.util.stream.Collectors

import static org.octopusden.octopus.escrow.configuration.validation.GroovySlurperConfigValidator.ESCROW
import static org.octopusden.octopus.escrow.configuration.validation.GroovySlurperConfigValidator.VCS_SETTINGS
import static org.octopusden.octopus.escrow.configuration.validation.GroovySlurperConfigValidator.REPOSITORY_TYPE
import static org.octopusden.octopus.escrow.configuration.validation.GroovySlurperConfigValidator.TAG
import static org.octopusden.octopus.escrow.configuration.validation.GroovySlurperConfigValidator.VCS_URL
import static org.octopusden.octopus.escrow.configuration.validation.GroovySlurperConfigValidator.BRANCH
import static org.octopusden.octopus.escrow.configuration.validation.GroovySlurperConfigValidator.HOTFIX_BRANCH
import static org.octopusden.octopus.escrow.configuration.validation.GroovySlurperConfigValidator.SECURITY_GROUPS_READ
import static org.octopusden.octopus.escrow.configuration.validation.GroovySlurperConfigValidator.SUPPORTED_ATTRIBUTES
import static org.octopusden.octopus.escrow.configuration.validation.GroovySlurperConfigValidator.JIRA
import static org.octopusden.octopus.escrow.configuration.validation.GroovySlurperConfigValidator.BUILD
import static org.octopusden.octopus.escrow.configuration.validation.GroovySlurperConfigValidator.TOOLS
import static org.octopusden.octopus.escrow.configuration.validation.GroovySlurperConfigValidator.DISTRIBUTION



class EscrowConfigurationLoader {
    private static final Logger LOG = LogManager.getLogger(EscrowConfigurationLoader.class)
    private static final ComponentHotfixSupportResolver COMPONENT_HOTFIX_SUPPORT_RESOLVER = new ComponentHotfixSupportResolver()
    public static final String FAKE_VCS_URL_FOR_BS20 = "fakeUrl"
    public static final String ALL_VERSIONS = "(,0),[0,)"

    private final IConfigLoader configLoader
    private final List<String> supportedGroupIds
    private final List<String> supportedSystems
    private final VersionNames versionNames

    EscrowConfigurationLoader(IConfigLoader configLoader,
                              List<String> supportedGroupIds,
                              List<String> supportedSystems,
                              VersionNames versionNames) {
        this.configLoader = configLoader
        this.supportedGroupIds = supportedGroupIds
        this.supportedSystems = supportedSystems
        this.versionNames = versionNames
    }

    VersionNames getVersionNames() {
        versionNames
    }

    EscrowModuleConfig loadModuleConfiguration(ComponentVersion componentRelease, Map<String, String> params) {
        ConfigObject configObject = configLoader.loadModuleConfig(params)
        def configuration = parseAndValidateConfiguration(configObject, false)
        return getEscrowModuleConfig(configuration, componentRelease)
    }

    EscrowModuleConfig loadModuleConfigurationIgnoreValidationForUnknownFields(ComponentVersion componentRelease, Map<String, String> params) {
        EscrowConfiguration configuration = loadFullConfigurationWithoutValidationForUnknownAttributes(params)
        return getEscrowModuleConfig(configuration, componentRelease)
    }

    static EscrowModuleConfig getEscrowModuleConfig(EscrowConfiguration configuration, ComponentVersion componentRelease) {
        resolveComponentConfiguration(configuration, componentRelease)
    }

    /**
     * Resolve component configuration (substitute variable, resolve configuration and etc).
     * @param escrowConfiguration escrow configuration
     * @param componentKey component key
     * @return resolved component configuration
     */
    static EscrowModuleConfig resolveComponentConfiguration(EscrowConfiguration escrowConfiguration, ComponentVersion componentV) {
        def version = componentV.version
        def componentKey = componentV.componentName
        def numericVersionFactory = new NumericVersionFactory(escrowConfiguration.versionNames)
        def versionInfo = numericVersionFactory.create(version)
        def versionRangeFactory = new VersionRangeFactory(escrowConfiguration.versionNames)
        def modules = escrowConfiguration.escrowModules.get(componentKey)?.moduleConfigurations?.stream()?.filter {
            moduleConfiguration -> versionRangeFactory.create(moduleConfiguration.versionRangeString).containsVersion(versionInfo)
        }?.collect(Collectors.toList())
        if (modules == null || modules.isEmpty()) {
            LOG.warn("There is no component {}:{} module", componentKey, version)
            return null
        }
        if (modules.size() > 1) {
            throw new ComponentResolverException("Too many component $componentKey:$version modules")
        }
        def config = modules[0]

        def normalizedVersion = normalizeVersion(version, config.jiraConfiguration, config.vcsSettings, escrowConfiguration.versionNames, false)
        if (normalizedVersion == null) {
            LOG.warn("Version of component {}:{} is incorrect", componentKey, version)
            return null
        }

        def escrowModuleConfig = config.clone()
        def postProcessor = new ModelConfigPostProcessor(ComponentVersion.create(componentKey, normalizedVersion), escrowConfiguration.versionNames)
        escrowModuleConfig.distribution = calculateDistribution(postProcessor.resolveDistribution(config.distribution), normalizedVersion)

        escrowModuleConfig.jiraConfiguration = postProcessor.resolveJiraConfiguration(config.jiraConfiguration)
        escrowModuleConfig
    }

    static String normalizeVersion(String version, JiraComponent jiraComponent, VCSSettings vcsSettings, VersionNames versionNames,
                                   boolean strict) {
         def jiraComponentVersionFormatter = new JiraComponentVersionFormatter(versionNames)
         return jiraComponentVersionFormatter.normalizeVersion(jiraComponent, version, strict,
                 COMPONENT_HOTFIX_SUPPORT_RESOLVER.isHotFixEnabled(vcsSettings)  )
    }

    static Distribution calculateDistribution(Distribution distribution, String version) {
        if (distribution == null || distribution.docker() == null) {
            return distribution
        }

        def docker = distribution.docker()

        docker = docker.split(',').collect { img ->
            def parts = img.split(":")
            def base = parts[0]
            def suffix = parts.size() > 1 ? "-${parts[1]}" : ""
            return "${base}:${version}${suffix}"
        }.join(",")

        return new Distribution(
                distribution.explicit(),
                distribution.external(),
                distribution.GAV(),
                distribution.DEB(),
                distribution.RPM(),
                docker,
                new SecurityGroups(distribution.securityGroups?.read)
        )

    }

    EscrowConfiguration loadFullConfiguration(Map<String, String> params) {
        LOG.info("Loading full configuration with params {}", params)
        ConfigObject configObject = configLoader.loadModuleConfig(params)
        return parseAndValidateConfiguration(configObject, false)
    }

    @TypeChecked
    EscrowConfiguration loadFullConfigurationWithoutValidationForUnknownAttributes(Map<String, String> params) {
        LOG.info("Loading full configuration with params " + params)
        ConfigObject configObject = configLoader.loadModuleConfigWithoutValidationForUnknownAttributes(params)
        return parseAndValidateConfiguration(configObject, true)
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    private EscrowConfiguration parseAndValidateConfiguration(ConfigObject rootObject, boolean ignoreUnknownAttributes) {
        List<Tool> toolsConfiguration = getToolsConfiguration(rootObject)
        DefaultConfigParameters commonDefaultConfiguration = getCommonDefaultConfiguration(rootObject, toolsConfiguration)
        Objects.requireNonNull(commonDefaultConfiguration)

        def fullConfig = new EscrowConfiguration(versionNames: versionNames)

        def components = loadComponentsFromConfigObject(rootObject, commonDefaultConfiguration, toolsConfiguration, ignoreUnknownAttributes)
        components.each { EscrowModule escrowComponent ->
            fullConfig.escrowModules.put(escrowComponent.moduleName, escrowComponent)
        }

        LOG.debug("Loading DSL")
        def dslComponents = configLoader.loadDslDefinedComponents()
        LOG.info("Loaded ${dslComponents.size()} DSL components")

        EscrowConfigValidator validator = new EscrowConfigValidator(supportedGroupIds, supportedSystems, versionNames, configLoader.loadDistributionValidationExcludedComponents())

        dslComponents.forEach {component ->
            LOG.debug("processing dsl $component")
            validator.validateEscrow(component, fullConfig)
            mergeGroovyAndDslComponent(component, fullConfig)
            component.subComponents.forEach { name, subComponent -> mergeGroovyAndDslSubComponent(subComponent, fullConfig)}
        }

        if (!ignoreUnknownAttributes) {
            validator.validateEscrowConfiguration(fullConfig)
            if (validator.hasErrors()) {
                StringBuilder errorBuff = new StringBuilder()
                validator.getErrors().each {
                    errorBuff.append "\n$it"
                }
                throw new EscrowConfigurationException("Validation of module config failed due following errors: $errorBuff")
            }
        }
        //Verify that VersionComponent could be requested
        fullConfig.getEscrowModules().values().forEach { escrowModule -> escrowModule.moduleConfigurations.forEach { it.toVersionedComponent() } }
        return fullConfig
    }

    void mergeGroovyAndDslComponent(Component dslComponent, EscrowConfiguration escrowConfiguration) {
        def moduleConfigurations = escrowConfiguration.escrowModules.get(dslComponent.name).moduleConfigurations
        if (dslComponent.productType) {
            moduleConfigurations.forEach { moduleConfiguration -> moduleConfiguration.productType = dslComponent.productType }
        }
        if (dslComponent?.build?.dependencies) {
            moduleConfigurations.forEach { moduleConfiguration -> moduleConfiguration.buildConfiguration.dependencies = dslComponent.build.dependencies }
        }
        mergeGroovyAndDslSubComponent(dslComponent, escrowConfiguration)
    }

    void mergeGroovyAndDslSubComponent(SubComponent dslComponent, EscrowConfiguration escrowConfiguration) {
        def moduleConfigurations = escrowConfiguration.escrowModules.get(dslComponent.name).moduleConfigurations
        moduleConfigurations.forEach { moduleConfiguration -> mergeComponents(dslComponent, moduleConfiguration) }
        dslComponent.versions.forEach { dslVersionRange, dslVersionedComponent ->
            def versionedEscrowModule = moduleConfigurations.find { moduleConfiguration -> moduleConfiguration.versionRangeString == dslVersionRange }
            if (!versionedEscrowModule) {
                throw new EscrowConfigurationException("The DSL version range $dslVersionRange is missed in groovy configuration of the component ${dslComponent.name}")
            }
            mergeComponents(dslVersionedComponent, versionedEscrowModule)
        }
    }

    private void mergeComponents(VersionedComponentConfiguration dslComponent, EscrowModuleConfig escrowModuleConfig) {
        if (dslComponent.escrow) {
            escrowModuleConfig.escrow = dslComponent.escrow
        }

        if (dslComponent.build) {
            escrowModuleConfig.buildConfiguration.buildTools.with {
                clear()
                addAll(dslComponent.build.tools)
            }
        }
    }

    private List<EscrowModule> loadComponentsFromConfigObject(ConfigObject rootObject, DefaultConfigParameters commonDefaultConfiguration, List<Tool> tools, boolean ignoreUnknownAttributes) {
        List<EscrowModule> escrowModules = []
        List<String> componentList = getComponentList(rootObject)
        LOG.debug("sub-components of are $componentList")
        componentList.each { String componentName ->
            def escrowModulesFromComponent = loadComponent(rootObject, componentName, commonDefaultConfiguration, tools, ignoreUnknownAttributes)
            escrowModules.addAll(escrowModulesFromComponent)
        }
        escrowModules
    }

    static List<String> getComponentList(ConfigObject configObject) {
        List<String> componentList = []
        configObject.each { Object key, value ->
            if (key != ReleaseInfoResolver.DEFAULT_SETTINGS && key != ReleaseInfoResolver.TOOLS_SETTINGS && value instanceof ConfigObject && !((ConfigObject) value).isEmpty()) {
                componentList.add(key.toString())
            }
        }
        componentList
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    private List<EscrowModule> loadComponent(ConfigObject rootObject, String moduleName,
                                             DefaultConfigParameters defaultConfiguration, List<Tool> tools,
                                             boolean ignoreUnknownAttributes) {
        LOG.debug("Loading configuration of $moduleName")
        def escrowModule = new EscrowModule(moduleName: moduleName)
        ConfigObject moduleConfigObject = rootObject."$moduleName" as ConfigObject

        DefaultConfigParameters componentDefaultConfiguration = loadDefaultComponentConfiguration(moduleName, moduleConfigObject, defaultConfiguration, tools)
        def components = loadSubComponents(componentDefaultConfiguration, defaultConfiguration, tools, moduleConfigObject, ignoreUnknownAttributes)

        if (!moduleConfigObject.isEmpty()) {
            for (moduleConfigItem in moduleConfigObject) {

                def moduleConfigItemName = moduleConfigItem.key
                if (SUPPORTED_ATTRIBUTES.contains(moduleConfigItemName) ||
                        moduleConfigItemName == JIRA ||
                        moduleConfigItemName == BUILD ||
                        moduleConfigItemName == DISTRIBUTION ||
                        moduleConfigItemName == TOOLS ||
                        moduleConfigItemName == VCS_SETTINGS ||
                        moduleConfigItemName == ESCROW
                ) {
                    continue  //TODO: bad style
                }

                if ("components".equalsIgnoreCase(moduleConfigItemName as String)) {
                    continue //TODO
                }
                if (moduleConfigItem.value instanceof String && ignoreUnknownAttributes) {
                    continue
                }

                ConfigObject moduleConfigSection = moduleConfigItem.value as ConfigObject
                def buildSystem = moduleConfigSection.containsKey("buildSystem") ? BuildSystem.valueOf(moduleConfigSection.buildSystem.toString()) :
                        componentDefaultConfiguration.buildSystem

                def vcsSettingsWrapper = loadVCSSettings(moduleConfigSection, componentDefaultConfiguration, buildSystem)
                boolean isHotfixEnabled = COMPONENT_HOTFIX_SUPPORT_RESOLVER.isHotFixEnabled(vcsSettingsWrapper.vcsSettings)


                JiraComponent jiraConfiguration = loadJiraConfiguration(moduleConfigSection, componentDefaultConfiguration.jiraComponent, isHotfixEnabled)
                BuildParameters buildConfiguration = loadBuildConfiguration(moduleConfigSection, componentDefaultConfiguration.buildParameters, tools)
                Distribution distributionConfiguration = loadDistribution(moduleConfigSection, componentDefaultConfiguration.distribution)
                String componentOwner = loadComponentOwner(moduleConfigSection, componentDefaultConfiguration.componentOwner)
                final String releaseManager = loadComponentReleaseManager(moduleConfigSection, componentDefaultConfiguration.releaseManager)
                final String securityChampion = loadComponentSecurityChampion(moduleConfigSection, componentDefaultConfiguration.securityChampion)
                final String system = loadComponentSystem(moduleConfigSection, componentDefaultConfiguration.system)
                final String clientCode = loadComponentClientCode(moduleConfigSection, componentDefaultConfiguration.clientCode)
                final String parentComponent = loadComponentParentComponent(moduleConfigSection, componentDefaultConfiguration.parentComponent)
                final Boolean releasesInDefaultBranch = loadReleasesInDefaultBranch(moduleConfigSection, componentDefaultConfiguration.releasesInDefaultBranch)
                final Boolean solution = loadSolution(moduleConfigSection, componentDefaultConfiguration.solution)
                final String componentDisplayName = loadComponentDisplayName(moduleConfigSection, componentDefaultConfiguration.componentDisplayName)
                final String octopusVersion = loadVersion(moduleConfigSection, componentDefaultConfiguration.octopusVersion, LoaderInheritanceType.VERSION_RANGE.octopusVersionInherit)

                def versionRange = parseVersionRange(moduleConfigItemName.toString(), moduleName)
                def buildFileLocation = moduleConfigSection.containsKey("buildFilePath") ? moduleConfigSection.buildFilePath.toString() :
                        componentDefaultConfiguration.buildFilePath
                def escrow = loadEscrow(moduleConfigSection, componentDefaultConfiguration.escrow)

                def escrowModuleConfiguration = new EscrowModuleConfig(buildSystem: buildSystem,
                        groupIdPattern: moduleConfigSection.containsKey("groupId") ? moduleConfigSection.groupId : componentDefaultConfiguration.groupIdPattern,
                        artifactIdPattern: moduleConfigSection.containsKey("artifactId") ? moduleConfigSection.artifactId.toString().trim() :
                                componentDefaultConfiguration.artifactIdPattern,
                        versionRange: versionRange,
                        buildFilePath: buildFileLocation,
                        componentDisplayName: componentDisplayName,
                        componentOwner: componentOwner,
                        releaseManager: releaseManager,
                        securityChampion: securityChampion,
                        system: system,
                        clientCode: clientCode,
                        releasesInDefaultBranch: releasesInDefaultBranch,
                        solution: solution,
                        parentComponent: parentComponent,
                        jiraConfiguration: jiraConfiguration,
                        buildConfiguration: buildConfiguration?.clone(),
                        deprecated: moduleConfigSection.containsKey("deprecated") ? moduleConfigSection.deprecated : componentDefaultConfiguration.deprecated,
                        vcsSettings: vcsSettingsWrapper.vcsSettings,
                        distribution: distributionConfiguration,
                        octopusVersion: octopusVersion,
                        escrow: escrow
                )
                escrowModule.moduleConfigurations.add(escrowModuleConfiguration)
            }
            if (escrowModule.moduleConfigurations.isEmpty()) {
                def escrowModuleConfiguration = new EscrowModuleConfig(
                        buildSystem: componentDefaultConfiguration.getBuildSystem(),
                        groupIdPattern: componentDefaultConfiguration.groupIdPattern,
                        artifactIdPattern: componentDefaultConfiguration.artifactIdPattern,
                        versionRange: ALL_VERSIONS,
                        componentDisplayName: componentDefaultConfiguration.componentDisplayName,
                        componentOwner: componentDefaultConfiguration.componentOwner,
                        releaseManager: componentDefaultConfiguration.releaseManager,
                        securityChampion: componentDefaultConfiguration.securityChampion,
                        system: componentDefaultConfiguration.system,
                        clientCode: componentDefaultConfiguration.clientCode,
                        releasesInDefaultBranch: componentDefaultConfiguration.releasesInDefaultBranch,
                        solution: componentDefaultConfiguration.solution,
                        parentComponent: componentDefaultConfiguration.parentComponent,
                        buildFilePath: componentDefaultConfiguration.getBuildFilePath(),
                        jiraConfiguration: componentDefaultConfiguration.jiraComponent,
                        buildConfiguration: componentDefaultConfiguration.buildParameters?.clone(),
                        deprecated: componentDefaultConfiguration.deprecated,
                        vcsSettings: componentDefaultConfiguration.vcsSettingsWrapper.vcsSettings,
                        distribution: componentDefaultConfiguration.distribution,
                        octopusVersion: componentDefaultConfiguration.octopusVersion,
                        escrow: componentDefaultConfiguration.escrow
                )
                escrowModule.moduleConfigurations.add(escrowModuleConfiguration)
            }
        }
        components.add(escrowModule)
        components
    }

    static VCSSettingsWrapper parseVCSSettingsSection(ConfigObject vcsSettingsObject,
                                                      VCSSettingsWrapper parentVCSSettings) {
        def vcsRoots = []
        def (defaultVCSSettings, _) = loadVCSRoot("main", vcsSettingsObject, parentVCSSettings, null)
        def defaultVCSRoot = getDefaultRoot(defaultVCSSettings)
        def vscRootName2ParametersFromDefaultsMap = [:]
        vcsSettingsObject.each { vcsRootName, value ->
            if (value instanceof ConfigObject) {
                def (pureVCSSettings, currentVCSRootParametersFromDefault) = loadVCSRoot(vcsRootName as String, value as ConfigObject, parentVCSSettings, defaultVCSRoot)
                def pureVCSRoot = pureVCSSettings.getVersionControlSystemRoots()?.isEmpty() ? null : pureVCSSettings.getVersionControlSystemRoots().get(0)
                if (pureVCSRoot != null) {
                    vscRootName2ParametersFromDefaultsMap[vcsRootName] = currentVCSRootParametersFromDefault
                    final vcsRoot = pureVCSRoot
                    vcsRoots.add(vcsRoot)
                }
            }
        }
        if (vcsRoots.isEmpty() && defaultVCSRoot != null
                && defaultVCSRoot.isFullyConfigured()) {
            vcsRoots.add(defaultVCSRoot)
        }

        List<VersionControlSystemRoot> componentRoots = replaceDefaults(parentVCSSettings, vscRootName2ParametersFromDefaultsMap, defaultVCSRoot, vcsRoots)


        return new VCSSettingsWrapper(vcsSettings: VCSSettings.create(defaultVCSSettings?.externalRegistry, componentRoots),
                defaultVCSSettings: defaultVCSRoot, vscRootName2ParametersFromDefaultsMap: vscRootName2ParametersFromDefaultsMap)
    }

    static RepositoryType detectRepositoryType(final String vcsUrl, final RepositoryType defaultRepositoryType) {
        if (vcsUrl == null) {
            return defaultRepositoryType
        }
        if (vcsUrl.contains("git@")) {
            return RepositoryType.GIT
        }
        if (vcsUrl.startsWith("ssh://hg@mercurial")) {
            return RepositoryType.MERCURIAL
        }
        return defaultRepositoryType
    }
    static List<VersionControlSystemRoot> replaceDefaults(VCSSettingsWrapper parentVCSSettings,
                                                          Map<String, List<String>> vcsRootName2ParametersFromDefaultMap,
                                                          VersionControlSystemRoot currentDefaultVCSParameters,
                                                          List<VersionControlSystemRoot> vcsRoots) {
        def parentVCSRoots = parentVCSSettings?.vcsSettings?.versionControlSystemRoots == null ? [] :
                parentVCSSettings?.vcsSettings?.versionControlSystemRoots

        def roots = parentVCSRoots.collect { parentRoot ->
            if (vcsRoots.every { root -> root.name != parentRoot.name }) {
                String newBranch = parentVCSSettings.vscRootName2ParametersFromDefaultsMap[parentRoot.name]?.contains(BRANCH) ?
                        currentDefaultVCSParameters.branch : parentRoot.branch
                String newHotfixBranch = parentVCSSettings.vscRootName2ParametersFromDefaultsMap[parentRoot.name]?.contains(HOTFIX_BRANCH) ?
                        currentDefaultVCSParameters.hotfixBranch : parentRoot.hotfixBranch
                String newTag = parentVCSSettings.vscRootName2ParametersFromDefaultsMap[parentRoot.name]?.contains(TAG) ?
                        currentDefaultVCSParameters.tag : parentRoot.tag
                String newUrl = parentVCSSettings.vscRootName2ParametersFromDefaultsMap[parentRoot.name]?.contains(VCS_URL) ?
                        currentDefaultVCSParameters.vcsPath : parentRoot.vcsPath
                RepositoryType newRepositoryType = parentVCSSettings.vscRootName2ParametersFromDefaultsMap[parentRoot.name]?.contains(REPOSITORY_TYPE) ?
                        currentDefaultVCSParameters.repositoryType : parentRoot.repositoryType
                VersionControlSystemRoot.create(parentRoot.name, detectRepositoryType(newUrl, newRepositoryType), newUrl, newTag, newBranch, newHotfixBranch)
            } else {
                def root = vcsRoots.find { parentRoot.name == it.name }
                // branch
                String newBranch = vcsRootName2ParametersFromDefaultMap[root.name]?.contains(BRANCH) ?
                        (parentVCSSettings.vscRootName2ParametersFromDefaultsMap[root.name]?.contains(BRANCH) ?
                                currentDefaultVCSParameters.branch : parentRoot.branch) :
                        root.branch
                // hotfix branch
                String newHotfixBranch = vcsRootName2ParametersFromDefaultMap[root.name]?.contains(HOTFIX_BRANCH) ?
                        (parentVCSSettings.vscRootName2ParametersFromDefaultsMap[root.name]?.contains(HOTFIX_BRANCH) ?
                                currentDefaultVCSParameters.hotfixBranch : parentRoot.hotfixBranch) :
                        root.hotfixBranch
                // tag
                String newTag = vcsRootName2ParametersFromDefaultMap[root.name]?.contains(TAG) ?
                        (parentVCSSettings.vscRootName2ParametersFromDefaultsMap[root.name]?.contains(TAG) ?
                                currentDefaultVCSParameters.tag : parentRoot.tag) :
                        root.tag
                // url
                String newUrl = vcsRootName2ParametersFromDefaultMap[root.name]?.contains(VCS_URL) ?
                        (parentVCSSettings.vscRootName2ParametersFromDefaultsMap[root.name]?.contains(VCS_URL) ?
                                currentDefaultVCSParameters.vcsPath : parentRoot.vcsPath) :
                        root.vcsPath
                // type
                RepositoryType newRepositoryType = vcsRootName2ParametersFromDefaultMap[root.name]?.contains(REPOSITORY_TYPE) ?
                        (parentVCSSettings.vscRootName2ParametersFromDefaultsMap[root.name]?.contains(REPOSITORY_TYPE) ?
                                currentDefaultVCSParameters.repositoryType : parentRoot.repositoryType) :
                        root.repositoryType
                VersionControlSystemRoot.create(parentRoot.name, detectRepositoryType(newUrl, newRepositoryType), newUrl, newTag, newBranch, newHotfixBranch)
            }
        }
        roots + vcsRoots.findAll { root -> parentVCSRoots.every { parentRoot -> parentRoot.name != root.name } }
    }

//    @TypeChecked
    private
    static VCSSettingsWrapper loadVCSSettings(ConfigObject moduleConfigSection, DefaultConfigParameters componentDefaultConfiguration, BuildSystem buildSystem) {
        if (moduleConfigSection.containsKey(VCS_SETTINGS)) {
            return parseVCSSettingsSection(moduleConfigSection.get(VCS_SETTINGS) as ConfigObject,
                    componentDefaultConfiguration.vcsSettingsWrapper)
        } else {
            def (defaultVCSSettingsBeforeBS2_0Processing, _) = loadVCSRoot("main", moduleConfigSection,
                    componentDefaultConfiguration?.vcsSettingsWrapper, null)
            def defaultVCSRootBeforeBS2_0Processing = getDefaultRoot(defaultVCSSettingsBeforeBS2_0Processing)
            def defaultVCSRoot = (buildSystem == BuildSystem.BS2_0 && defaultVCSRootBeforeBS2_0Processing != null) ?
                    VersionControlSystemRoot.create(defaultVCSRootBeforeBS2_0Processing.name,
                            RepositoryType.CVS,
                            FAKE_VCS_URL_FOR_BS20,
                            defaultVCSRootBeforeBS2_0Processing.tag,
                            defaultVCSRootBeforeBS2_0Processing.branch,
                            defaultVCSRootBeforeBS2_0Processing.hotfixBranch)
                    : defaultVCSRootBeforeBS2_0Processing
            List<VersionControlSystemRoot> componentRoots = replaceDefaults(componentDefaultConfiguration?.vcsSettingsWrapper,
                    [:], defaultVCSRoot, defaultVCSRoot?.isFullyConfigured() ? [defaultVCSRoot] : [])

            return new VCSSettingsWrapper(vcsSettings:
                    VCSSettings.create(
                            componentDefaultConfiguration?.vcsSettingsWrapper?.vcsSettings?.externalRegistry,
                            componentRoots
                    ),
                    defaultVCSSettings: defaultVCSRoot,
                    vscRootName2ParametersFromDefaultsMap: [:])
        }
    }

    private static VersionControlSystemRoot getDefaultRoot(VCSSettings vcsSettings) {
        if (vcsSettings.getVersionControlSystemRoots() != null && !vcsSettings.getVersionControlSystemRoots().isEmpty()) {
            vcsSettings.getSingleVCSRoot()
        } else {
            null
        }
    }

    private
    static Tuple2<VCSSettings, List<String>> loadVCSRoot(String name, ConfigObject moduleConfigSection,
                                                         VCSSettingsWrapper defaultVCSSettingsWrapper,
                                                         VersionControlSystemRoot currentDefault) {

        VersionControlSystemRoot defaultVCSParameters = defaultVCSSettingsWrapper?.defaultVCSSettings

// legacy configuration
        def repositoryTypeDefined = moduleConfigSection.containsKey(REPOSITORY_TYPE)
        RepositoryType repositoryType = repositoryTypeDefined ?
                RepositoryType.valueOf(moduleConfigSection.repositoryType.toString()) :
                currentDefault?.repositoryType == null ? defaultVCSParameters?.repositoryType : currentDefault.repositoryType

        def externalRegistry = moduleConfigSection.containsKey("externalRegistry") ? moduleConfigSection.externalRegistry?.toString() :
                defaultVCSSettingsWrapper?.vcsSettings?.externalRegistry

        def tagDefined = moduleConfigSection.containsKey(TAG)
        String tag = tagDefined ? moduleConfigSection.tag :
                currentDefault?.tag == null ? defaultVCSParameters?.tag : currentDefault.tag

        def vcsUrlDefined = moduleConfigSection.containsKey(VCS_URL)
        String vcsUrl = vcsUrlDefined ? moduleConfigSection.vcsUrl :
                currentDefault?.vcsPath == null ? defaultVCSParameters?.vcsPath : currentDefault.vcsPath

        def branchDefined = moduleConfigSection.containsKey(BRANCH)
        String branch = branchDefined ? moduleConfigSection.branch :
                currentDefault?.rawBranch == null ? defaultVCSParameters?.rawBranch : currentDefault.rawBranch

        def hotfixBranchDefined = moduleConfigSection.containsKey("hotfixBranch")
        String hotfixBranch = hotfixBranchDefined ? moduleConfigSection.hotfixBranch :
                currentDefault?.hotfixBranch == null ? defaultVCSParameters?.hotfixBranch : currentDefault.hotfixBranch

        def defaultParameters = []
        if (!repositoryTypeDefined) {
            defaultParameters << REPOSITORY_TYPE
        }
        if (!tagDefined) {
            defaultParameters << TAG
        }
        if (!branchDefined) {
            defaultParameters << BRANCH
        }
        if (!hotfixBranchDefined) {
            defaultParameters << HOTFIX_BRANCH
        }

        if (!vcsUrlDefined) {
            defaultParameters << VCS_URL
        }

        if (name == "main" && repositoryType == null && vcsUrl == null && tag == null && branch == null && hotfixBranch == null) {
            return [VCSSettings.create(externalRegistry, null), []]
        }
        return [VCSSettings.create(externalRegistry, [VersionControlSystemRoot.create(name, detectRepositoryType(vcsUrl, repositoryType), vcsUrl, tag, branch, hotfixBranch)]), defaultParameters]
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    List<EscrowModule> loadSubComponents(
            final DefaultConfigParameters defaultConfigParameters,
            DefaultConfigParameters commonDefaults,
            List<Tool> tools, ConfigObject moduleConfigObject, boolean ignoreUnknownAttributes) {
        DefaultConfigParameters defaultSubComponentsParameters = updateDefaultsForSubComponents(defaultConfigParameters, commonDefaults)

        if (moduleConfigObject.containsKey("components")) {
            def componentsObject = moduleConfigObject.get("components") as ConfigObject
            return loadComponentsFromConfigObject(componentsObject, defaultSubComponentsParameters, tools, ignoreUnknownAttributes)
        }
        []
    }

    private
    static DefaultConfigParameters updateDefaultsForSubComponents(DefaultConfigParameters defaultConfigParameters,
                                                                  DefaultConfigParameters commonDefaults) {
        DefaultConfigParameters defaultSubComponentsParameters = defaultConfigParameters.clone() as DefaultConfigParameters
        defaultSubComponentsParameters.buildSystem = commonDefaults.buildSystem
        defaultSubComponentsParameters.componentDisplayName = commonDefaults.componentDisplayName
        defaultSubComponentsParameters.vcsSettingsWrapper = buildBranchInheritedWrapper(defaultConfigParameters.vcsSettingsWrapper, commonDefaults.vcsSettingsWrapper)
        defaultSubComponentsParameters.artifactIdPattern = commonDefaults.artifactIdPattern
        def originalDeps = defaultSubComponentsParameters?.buildParameters?.dependencies
        defaultSubComponentsParameters.buildParameters = commonDefaults.clone().buildParameters
        if (originalDeps) {
            defaultSubComponentsParameters.buildParameters.dependencies = originalDeps
        }
        defaultSubComponentsParameters.distribution = commonDefaults.distribution
        defaultSubComponentsParameters
    }

    /**
     * Builds a new {@link VCSSettingsWrapper} for a 'sub-component' whose VCS branch should be inherited from its parent component
     */
    private static VCSSettingsWrapper buildBranchInheritedWrapper(
            VCSSettingsWrapper parentWrapper,
            VCSSettingsWrapper baseWrapper
    ) {
        def parentRoot = parentWrapper?.defaultVCSSettings
        if (parentRoot == null || parentRoot.branch == null) {
            return baseWrapper
        }
        List<VersionControlSystemRoot> newRoots =
                baseWrapper?.vcsSettings?.versionControlSystemRoots?.collect { r ->
                    VersionControlSystemRoot.create(
                            r.name,
                            r.repositoryType,
                            r.vcsPath,
                            r.tag,
                            parentRoot.branch,
                            r.hotfixBranch
                    )
                } ?: Collections.emptyList() as List<VersionControlSystemRoot>

        def baseDefault = baseWrapper?.defaultVCSSettings
        VersionControlSystemRoot newDefault = baseDefault == null ? null :
                VersionControlSystemRoot.create(
                        baseDefault.name,
                        baseDefault.repositoryType,
                        baseDefault.vcsPath,
                        baseDefault.tag,
                        parentRoot.branch,
                        baseDefault.hotfixBranch
                )
        def newVcsSettings = VCSSettings.create(
                baseWrapper?.vcsSettings?.externalRegistry,
                newRoots
        )
        return new VCSSettingsWrapper(
                vcsSettings: newVcsSettings,
                defaultVCSSettings: newDefault,
                vscRootName2ParametersFromDefaultsMap: baseWrapper?.vscRootName2ParametersFromDefaultsMap ?: [:]
        )
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    private
    static BuildParameters loadBuildConfiguration(ConfigObject parentConfigObject, BuildParameters defaultBuildParameters, List<Tool> tools) {
        final BuildParameters buildParameters
        if (parentConfigObject.containsKey("build")) {
            buildParameters = parseBuildSection(parentConfigObject.get("build") as ConfigObject, defaultBuildParameters, tools)
        } else {
            buildParameters = defaultBuildParameters?.clone()
        }
        return buildParameters?.clone()
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    static BuildParameters parseBuildSection(ConfigObject buildConfigObject, BuildParameters defaultBuildConfiguration, List<Tool> tools) {
        String javaVersion = buildConfigObject.containsKey("javaVersion") ? buildConfigObject.get("javaVersion") :
                defaultBuildConfiguration?.javaVersion
        String mavenVersion = buildConfigObject.containsKey("mavenVersion") ? buildConfigObject.get("mavenVersion") :
                defaultBuildConfiguration?.mavenVersion
        String gradleVersion = buildConfigObject.containsKey("gradleVersion") ? buildConfigObject.get("gradleVersion") :
                defaultBuildConfiguration?.gradleVersion
        boolean requiredProject = buildConfigObject.containsKey("requiredProject") ? Boolean.valueOf(buildConfigObject.get("requiredProject") as String) :
                Boolean.valueOf(defaultBuildConfiguration?.requiredProject)
        String systemProperties = buildConfigObject.containsKey("systemProperties") ? buildConfigObject.get("systemProperties") :
                defaultBuildConfiguration?.systemProperties
        String projectVersion = buildConfigObject.containsKey("projectVersion") ? buildConfigObject.get("projectVersion") : defaultBuildConfiguration?.projectVersion
        String buildTasks = buildConfigObject.containsKey("buildTasks") ? buildConfigObject.get("buildTasks") : defaultBuildConfiguration?.buildTasks

        List<Tool> componentTools
        if (buildConfigObject.containsKey("requiredTools")) {
            componentTools = getToolsByRequired(buildConfigObject.get("requiredTools") as String, tools)
        } else {
            componentTools = defaultBuildConfiguration?.tools == null ? Collections.emptyList() : defaultBuildConfiguration?.tools
        }

        def buildParameters = BuildParameters.create(javaVersion, mavenVersion, gradleVersion, requiredProject, projectVersion, systemProperties, buildTasks, componentTools, Collections.emptyList())
        if (buildConfigObject.containsKey("dependencies")) {
            buildParameters.dependencies = loadDependencies(buildConfigObject, defaultBuildConfiguration.dependencies)
        } else if (defaultBuildConfiguration?.dependencies != null) {
            buildParameters.dependencies = defaultBuildConfiguration.dependencies
        }
        buildParameters
    }

    static List<Tool> getToolsByRequired(String requiredTools, List<Tool> tools) {
        def split = requiredTools.split(",")
        return tools.findAll { split.contains(it.name) }
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    private
    static JiraComponent loadJiraConfiguration(ConfigObject parentConfigObject, JiraComponent defaultJiraParameters, boolean isHotfixEnabled) {
        JiraComponent jiraConfiguration = null
        if (parentConfigObject.containsKey("jira")) {
            jiraConfiguration = parseJiraSection(parentConfigObject.get("jira") as ConfigObject, defaultJiraParameters, isHotfixEnabled)
        } else {
            // legacy configuration
            def projectKey = parentConfigObject.containsKey("jiraProjectKey") ? parentConfigObject.jiraProjectKey : defaultJiraParameters?.getProjectKey()
            def majorVersionFormat = parentConfigObject.containsKey("jiraMajorVersionFormat") ?
                    parentConfigObject.jiraMajorVersionFormat : defaultJiraParameters?.componentVersionFormat?.majorVersionFormat
            def releaseVersionFormat = parentConfigObject.containsKey("jiraReleaseVersionFormat") ?
                    parentConfigObject.jiraReleaseVersionFormat : defaultJiraParameters?.componentVersionFormat?.releaseVersionFormat
            def buildVersionFormat = defaultJiraParameters?.componentVersionFormat?.buildVersionFormat
            def lineVersionFormat = defaultJiraParameters?.componentVersionFormat?.lineVersionFormat
            def hotfixVersionFormat = defaultJiraParameters?.componentVersionFormat?.hotfixVersionFormat
            if (StringUtils.isNotBlank(projectKey)) {
                jiraConfiguration = new JiraComponent(projectKey, defaultJiraParameters.displayName,
                        ComponentVersionFormat.create(majorVersionFormat, releaseVersionFormat, buildVersionFormat, lineVersionFormat, hotfixVersionFormat),
                        defaultJiraParameters.componentInfo, defaultJiraParameters.technical, isHotfixEnabled)
            }
        }
        jiraConfiguration
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    private static String loadComponentOwner(ConfigObject parentConfigObject, String defaultComponentOwner) {
        if (parentConfigObject.containsKey("componentOwner")) {
            return parentConfigObject.get("componentOwner")
        } else {
            return defaultComponentOwner
        }
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    private static String loadComponentReleaseManager(ConfigObject parentConfigObject, String defaultReleaseManager) {
        if (parentConfigObject.containsKey("releaseManager")) {
            return parentConfigObject.get("releaseManager")
        } else {
            return defaultReleaseManager
        }
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    private static String loadComponentSecurityChampion(ConfigObject parentConfigObject, String defaultSecurityChampion) {
        if (parentConfigObject.containsKey("securityChampion")) {
            return parentConfigObject.get("securityChampion")
        } else {
            return defaultSecurityChampion
        }
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    private static loadComponentSystem(ConfigObject parentConfigObject, String defaultSystem){
        if (parentConfigObject.containsKey("system")) {
            return parentConfigObject.get("system")
        } else {
            return defaultSystem
        }
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    private static loadComponentClientCode(ConfigObject parentConfigObject, String defaultClientCode){
        if (parentConfigObject.containsKey("clientCode")) {
            return parentConfigObject.get("clientCode")
        } else {
            return defaultClientCode
        }
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    private static loadReleasesInDefaultBranch(ConfigObject parentConfigObject, Boolean defaultReleasesInDefaultBranch){
        if (parentConfigObject.containsKey("releasesInDefaultBranch")) {
            return parentConfigObject.get("releasesInDefaultBranch")
        } else {
            return defaultReleasesInDefaultBranch
        }
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    private static Boolean loadSolution(ConfigObject parentConfigObject, Boolean defaultSolution) {
        return parentConfigObject.getOrDefault("solution", defaultSolution)
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    private static loadComponentParentComponent(ConfigObject parentConfigObject, String defaultParentComponent){
        if (parentConfigObject.containsKey("parentComponent")) {
            return parentConfigObject.get("parentComponent")
        } else {
            return defaultParentComponent
        }
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    private static String loadComponentDisplayName(ConfigObject parentConfigObject, String defaultComponentDisplayName) {
        if (parentConfigObject.containsKey("componentDisplayName")) {
            return parentConfigObject.get("componentDisplayName")
        } else {
            return defaultComponentDisplayName
        }
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    private static String loadVersion(ConfigObject parentConfigObject, String defaultComponentVersion, boolean inherit) {
        if (parentConfigObject.containsKey("octopusVersion")) {
            return parentConfigObject.get("octopusVersion")
        } else if (inherit) {
            return defaultComponentVersion
        } else {
            null
        }
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    private static Escrow loadEscrow(ConfigObject parentConfigObject, Escrow defaultEscrow) {
        if (parentConfigObject.containsKey("escrow")) {
            def raw = parentConfigObject.get("escrow")
            if (raw instanceof EscrowBean) {
                return raw as EscrowBean
            }
            def escrowConfig = raw as ConfigObject
            def generation = escrowConfig.get("generation") as EscrowGenerationMode
            def buildTask = escrowConfig.get("buildTask") as String
            def providedDependencies = escrowConfig.get("providedDependencies") as Collection<String> ?: []
            def diskSpaceRequirement = escrowConfig.get("diskSpaceRequirement") as Long
            def additionalSources = escrowConfig.get("additionalSources") as Collection<String> ?: []
            def reusable = escrowConfig.containsKey("reusable") ? escrowConfig.get("reusable") as Boolean : true
            return new EscrowBean(generation, buildTask, providedDependencies, diskSpaceRequirement, additionalSources, reusable)
        } else {
            return defaultEscrow != null ? defaultEscrow as EscrowBean : null
        }
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    private static Distribution loadDistribution(ConfigObject parentConfigObject, Distribution defaultDistribution) {
        if (parentConfigObject.containsKey("distribution")) {
            return parseDistributionSection(parentConfigObject.get("distribution") as ConfigObject, defaultDistribution)
        } else {
            return defaultDistribution
        }
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    private static Dependencies loadDependencies(ConfigObject parentConfigObject, Dependencies defaultDependencies) {
        parentConfigObject.containsKey("dependencies") ? parseDependenciesSection(parentConfigObject.get("dependencies") as ConfigObject, defaultDependencies) : defaultDependencies
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    private static SecurityGroups loadSecurityGroups(ConfigObject parentConfigObject, SecurityGroups defaultSecurityGroups) {
        parentConfigObject.containsKey("securityGroups") ? parseSecurityGroupsSection(parentConfigObject.get("securityGroups") as ConfigObject, defaultSecurityGroups) : defaultSecurityGroups
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    static SecurityGroups parseSecurityGroupsSection(ConfigObject securityGroupsConfigObject, SecurityGroups defaultSecurityGroups) {
        def read = securityGroupsConfigObject.containsKey(SECURITY_GROUPS_READ) ? (securityGroupsConfigObject.get(SECURITY_GROUPS_READ) as String) : defaultSecurityGroups?.read
        return new SecurityGroups(read)
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    private static List<Tool> loadTools(ConfigObject toolsConfigObject, List<Tool> defaultTools) {
        List<Tool> tools = []
        toolsConfigObject.each { String toolName, ConfigObject toolConfiguration ->
            def escrowEnvironmentVariable = toolConfiguration.get("escrowEnvironmentVariable")
            def sourceLocation = toolConfiguration.get("sourceLocation")
            def targetLocation = toolConfiguration.get("targetLocation")
            def installScript = toolConfiguration.get("installScript")
            tools.add(new Tool(name: toolName, escrowEnvironmentVariable: escrowEnvironmentVariable,
                    sourceLocation: sourceLocation, targetLocation: targetLocation, installScript: installScript))
        }
        return mergeTools(tools, defaultTools)
    }


    @TypeChecked(TypeCheckingMode.SKIP)
    static JiraComponent parseJiraSection(ConfigObject jiraConfigObject, JiraComponent defaultJiraConfiguration, boolean isHotFixEnabled ) {
        def projectKey = jiraConfigObject.containsKey("projectKey") ? jiraConfigObject.projectKey : defaultJiraConfiguration?.projectKey
        String displayName = jiraConfigObject.containsKey("displayName") ? jiraConfigObject.get("displayName") : defaultJiraConfiguration?.displayName
        Boolean technical = (jiraConfigObject.containsKey("technical") ? jiraConfigObject.get("technical") : defaultJiraConfiguration?.technical) ?: false

        def componentInfo = loadComponentInfo(jiraConfigObject, defaultJiraConfiguration?.componentInfo)
        def majorVersionFormat = jiraConfigObject.containsKey("majorVersionFormat") ? jiraConfigObject.majorVersionFormat : defaultJiraConfiguration?.componentVersionFormat?.majorVersionFormat
        def releaseVersionFormat = jiraConfigObject.containsKey("releaseVersionFormat") ? jiraConfigObject.releaseVersionFormat : defaultJiraConfiguration?.componentVersionFormat?.releaseVersionFormat
        def buildVersionFormat = jiraConfigObject.containsKey("buildVersionFormat") ? jiraConfigObject.buildVersionFormat : defaultJiraConfiguration?.componentVersionFormat?.buildVersionFormat
        def lineVersionFormat = jiraConfigObject.containsKey("lineVersionFormat") ? jiraConfigObject.lineVersionFormat : defaultJiraConfiguration?.componentVersionFormat?.lineVersionFormat
        def hotfixVersionFormat = jiraConfigObject.containsKey("hotfixVersionFormat") ? jiraConfigObject.hotfixVersionFormat : defaultJiraConfiguration?.componentVersionFormat?.hotfixVersionFormat
        return new JiraComponent(projectKey, displayName, ComponentVersionFormat.create(majorVersionFormat, releaseVersionFormat, buildVersionFormat, lineVersionFormat, hotfixVersionFormat),
                componentInfo, technical, isHotFixEnabled)
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    static Distribution parseDistributionSection(ConfigObject distributionConfigObject, Distribution defaultDistribution) {
        def explicit = distributionConfigObject.containsKey("explicit") ? distributionConfigObject.explicit : defaultDistribution.explicit()
        def external = distributionConfigObject.containsKey("external") ? distributionConfigObject.external : defaultDistribution.external()
        def GAV = distributionConfigObject.getOrDefault("GAV", defaultDistribution?.GAV()) as String
        def DEB = distributionConfigObject.getOrDefault("DEB", defaultDistribution?.DEB()) as String
        def RPM = distributionConfigObject.getOrDefault("RPM", defaultDistribution?.RPM()) as String
        def securityGroup = loadSecurityGroups(distributionConfigObject, defaultDistribution?.securityGroups)
        def docker = distributionConfigObject.getOrDefault("docker", defaultDistribution?.docker()) as String
        return new Distribution(explicit, external, GAV, DEB, RPM, docker, securityGroup)
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    static Dependencies parseDependenciesSection(ConfigObject dependenciesConfigObject, Dependencies defaultDistribution) {
        def autoUpdate = dependenciesConfigObject.containsKey("autoUpdate") ? dependenciesConfigObject.autoUpdate : defaultDistribution.autoUpdate
        new Dependencies(autoUpdate)
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    static ComponentInfo loadComponentInfo(ConfigObject parentConfigObject, ComponentInfo defaultComponentInfo) {
        if (parentConfigObject.containsKey("customer")) {
            return parseComponentInfo(parentConfigObject.get("customer") as ConfigObject, defaultComponentInfo)
        }
        if (parentConfigObject.containsKey("component")) {
            return parseComponentInfo(parentConfigObject.get("component") as ConfigObject, defaultComponentInfo)
        }
        return defaultComponentInfo
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    static ComponentInfo parseComponentInfo(ConfigObject componentInfoConfigObject, ComponentInfo defaultComponentInfo) {
        String versionPrefix = componentInfoConfigObject.containsKey("versionPrefix") ? componentInfoConfigObject.get("versionPrefix") : defaultComponentInfo?.versionPrefix
        String versionFormat = componentInfoConfigObject.containsKey("versionFormat") ? componentInfoConfigObject.get("versionFormat") : defaultComponentInfo?.versionFormat
        return new ComponentInfo(versionPrefix, versionFormat)
    }


    private static String parseVersionRange(String versionRangeStr, String moduleName) {
        Validate.notNull(versionRangeStr)
        Validate.notNull(moduleName)
        try {
            VersionRange.createFromVersionSpec(versionRangeStr)
            return versionRangeStr
        } catch (InvalidVersionSpecificationException e) {
            throw new EscrowConfigurationException("Invalid version range $versionRangeStr in configuration of $moduleName", e)
        }
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    static DefaultConfigParameters getCommonDefaultConfiguration(ConfigObject configObject, List<Tool> tools) {
        for (entry in configObject) {
            String moduleName = entry.key as String
            if (moduleName == ReleaseInfoResolver.DEFAULT_SETTINGS) {
                ConfigObject vcsConfigObject = entry.value as ConfigObject
                DefaultConfigParameters defaultConfigParameters = loadDefaultConfigurationFromConfigObject(moduleName, vcsConfigObject, new DefaultConfigParameters(), tools, LoaderInheritanceType.DEFAULT)
                return defaultConfigParameters
            }
        }
        return new DefaultConfigParameters()
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    static List<Tool> getToolsConfiguration(ConfigObject configObject) {
        for (entry in configObject) {
            String moduleName = entry.key as String
            if (moduleName == ReleaseInfoResolver.TOOLS_SETTINGS) {
                ConfigObject vcsConfigObject = entry.value as ConfigObject
                List<Tool> tools = loadTools(vcsConfigObject, [])
                return tools
            }
        }
        return Collections.emptyList()
    }


    static DefaultConfigParameters loadDefaultComponentConfiguration(String moduleName, ConfigObject moduleConfigObject,
                                                                     DefaultConfigParameters defaultConfigParameters,
                                                                     List<Tool> tools) {
        def pureComponentDefaults = loadDefaultConfigurationFromConfigObject(moduleName, moduleConfigObject, defaultConfigParameters, tools, LoaderInheritanceType.COMPONENT)



        boolean isHotfixEnabled = COMPONENT_HOTFIX_SUPPORT_RESOLVER.isHotFixEnabled(pureComponentDefaults.vcsSettingsWrapper.vcsSettings)

//        pureComponentDefaults.distribution = defaultConfigParameters.distribution
        String majorVersionFormat = pureComponentDefaults.jiraComponent?.componentVersionFormat?.majorVersionFormat != null ?
                pureComponentDefaults.jiraComponent?.componentVersionFormat?.majorVersionFormat :
                defaultConfigParameters.jiraComponent?.componentVersionFormat?.majorVersionFormat
        String releaseVersionFormat = pureComponentDefaults.jiraComponent?.componentVersionFormat?.releaseVersionFormat != null ?
                pureComponentDefaults.jiraComponent.componentVersionFormat?.releaseVersionFormat :
                defaultConfigParameters.jiraComponent?.componentVersionFormat?.releaseVersionFormat
        String buildVersionFormat = pureComponentDefaults.jiraComponent?.componentVersionFormat?.buildVersionFormat != null ?
                pureComponentDefaults.jiraComponent.componentVersionFormat?.buildVersionFormat :
                defaultConfigParameters.jiraComponent?.componentVersionFormat?.buildVersionFormat
        String lineVersionFormat = pureComponentDefaults.jiraComponent?.componentVersionFormat?.lineVersionFormat != null ?
                pureComponentDefaults.jiraComponent.componentVersionFormat?.lineVersionFormat :
                defaultConfigParameters.jiraComponent?.componentVersionFormat?.lineVersionFormat
        String hotfixVersionFormat = pureComponentDefaults.jiraComponent?.componentVersionFormat?.hotfixVersionFormat != null ?
                pureComponentDefaults.jiraComponent.componentVersionFormat?.hotfixVersionFormat :
                defaultConfigParameters.jiraComponent?.componentVersionFormat?.hotfixVersionFormat
        ComponentVersionFormat componentVersionFormat =
                majorVersionFormat != null ? ComponentVersionFormat.create(majorVersionFormat, releaseVersionFormat, buildVersionFormat, lineVersionFormat, hotfixVersionFormat) : null

        String versionFormat = pureComponentDefaults.jiraComponent?.componentInfo?.versionFormat != null ?
                pureComponentDefaults.jiraComponent?.componentInfo?.versionFormat :
                defaultConfigParameters.jiraComponent?.componentInfo?.versionFormat

        String displayName = pureComponentDefaults.jiraComponent?.displayName != null ?
                pureComponentDefaults.jiraComponent?.displayName :
                defaultConfigParameters.jiraComponent?.displayName

        String versionPrefix = pureComponentDefaults.jiraComponent?.componentInfo?.versionPrefix != null ?
                pureComponentDefaults.jiraComponent?.componentInfo?.versionPrefix :
                defaultConfigParameters.jiraComponent?.componentInfo?.versionPrefix

        ComponentInfo componentInfo = null
        if (pureComponentDefaults.jiraComponent?.componentInfo != null || defaultConfigParameters.jiraComponent?.componentInfo != null) {
            componentInfo = new ComponentInfo(versionPrefix, versionFormat)
        }

        pureComponentDefaults.jiraComponent =
                new JiraComponent(pureComponentDefaults?.jiraComponent?.projectKey, displayName, componentVersionFormat,
                        componentInfo, pureComponentDefaults?.jiraComponent?.technical ?: false, isHotfixEnabled)

        pureComponentDefaults.escrow = pureComponentDefaults.escrow != null ? pureComponentDefaults.escrow : defaultConfigParameters.escrow

        return pureComponentDefaults
    }

    private
    static List<Tool> mergeTools(List<Tool> tools, List<Tool> defaultTools) {
        List<Tool> mergedTools = []
        tools.each { tool ->
            Tool defaultTool = defaultTools.find { it.name == tool.name }
            String escrowEnvironmentVariable = tool.escrowEnvironmentVariable != null ? tool.escrowEnvironmentVariable : defaultTool?.escrowEnvironmentVariable
            String sourceLocation = tool.sourceLocation != null ? tool.sourceLocation : defaultTool?.sourceLocation
            String targetLocation = tool.targetLocation != null ? tool.targetLocation : defaultTool?.targetLocation
            String installScript = tool.installScript != null ? tool.installScript : defaultTool?.installScript
            mergedTools.add(new Tool(name: tool.name, escrowEnvironmentVariable: escrowEnvironmentVariable, sourceLocation: sourceLocation,
                    targetLocation: targetLocation, installScript: installScript))
        }
        defaultTools.each { tool ->
            Tool foundTool = tools.find { tool.name == it.name }
            if (foundTool == null) {
                mergedTools.add(tool)
            }
        }
        return mergedTools
    }

    private static DefaultConfigParameters loadDefaultConfigurationFromConfigObject(
            String moduleName,
            ConfigObject componentConfigObject,
            DefaultConfigParameters defaultConfiguration,
            List<Tool> tools,
            LoaderInheritanceType inheritanceType
    ) {
        BuildSystem buildSystem = componentConfigObject.containsKey("buildSystem") ? BuildSystem.valueOf(componentConfigObject.buildSystem.toString()) : defaultConfiguration?.buildSystem
        VCSSettingsWrapper vcsSettingsWrapper = loadVCSSettings(componentConfigObject, defaultConfiguration, buildSystem)

        boolean isHotfixEnabled = COMPONENT_HOTFIX_SUPPORT_RESOLVER.isHotFixEnabled(vcsSettingsWrapper.vcsSettings)

        JiraComponent jiraComponent = loadJiraConfiguration(componentConfigObject, defaultConfiguration.jiraComponent, isHotfixEnabled)
        BuildParameters buildParameters = loadBuildConfiguration(componentConfigObject, defaultConfiguration.buildParameters, tools)
        Distribution distribution = loadDistribution(componentConfigObject, defaultConfiguration.distribution)
        String componentDisplayName = loadComponentDisplayName(componentConfigObject, null)
        String componentOwner = loadComponentOwner(componentConfigObject, defaultConfiguration.componentOwner)
        final String releaseManager = loadComponentReleaseManager(componentConfigObject, defaultConfiguration.releaseManager)
        final String securityChampion = loadComponentSecurityChampion(componentConfigObject, defaultConfiguration.securityChampion)
        final String system = loadComponentSystem(componentConfigObject, defaultConfiguration.system)
        final String clientCode = loadComponentClientCode(componentConfigObject, defaultConfiguration.clientCode)
        final Boolean releasesInDefaultBranch = loadReleasesInDefaultBranch(componentConfigObject, defaultConfiguration.releasesInDefaultBranch)
        final Boolean solution = loadSolution(componentConfigObject, defaultConfiguration.solution)
        final String parentComponent = loadComponentParentComponent(componentConfigObject, defaultConfiguration.parentComponent)
        final String octopusVersion = loadVersion(componentConfigObject, defaultConfiguration.octopusVersion, inheritanceType.octopusVersionInherit)

        Escrow escrow = loadEscrow(componentConfigObject, defaultConfiguration.escrow)

        def defaultConfigParameters = new DefaultConfigParameters(buildSystem: buildSystem,
                groupIdPattern: componentConfigObject.containsKey("groupId") ? componentConfigObject.groupId : defaultConfiguration?.groupIdPattern,
                artifactIdPattern: componentConfigObject.containsKey("artifactId") ? componentConfigObject.artifactId : defaultConfiguration?.artifactIdPattern,
                componentDisplayName: componentDisplayName,
                componentOwner: componentOwner,
                releaseManager: releaseManager,
                securityChampion: securityChampion,
                system: system,
                clientCode: clientCode,
                releasesInDefaultBranch: releasesInDefaultBranch,
                solution: solution,
                parentComponent: parentComponent,
                jiraComponent: jiraComponent,
                buildParameters: buildParameters,
                buildFilePath: componentConfigObject.containsKey("buildFilePath") ? componentConfigObject.buildFilePath : null,
                deprecated: componentConfigObject.containsKey("deprecated") ? componentConfigObject.deprecated : false,
                distribution: distribution,
                vcsSettingsWrapper: vcsSettingsWrapper,
                octopusVersion: octopusVersion,
                escrow: escrow
        )
        defaultConfigParameters
    }
}
