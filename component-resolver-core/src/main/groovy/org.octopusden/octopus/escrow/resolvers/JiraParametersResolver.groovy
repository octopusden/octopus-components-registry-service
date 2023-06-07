package org.octopusden.octopus.escrow.resolvers

import org.octopusden.octopus.escrow.JiraProjectVersion
import org.octopusden.octopus.escrow.ModelConfigPostProcessor
import org.octopusden.octopus.escrow.config.ComponentConfig
import org.octopusden.octopus.escrow.config.JiraComponentVersionRange
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.octopus.escrow.configuration.model.EscrowConfiguration
import org.octopusden.octopus.escrow.configuration.model.EscrowModule
import org.octopusden.octopus.escrow.configuration.model.EscrowModuleConfig
import org.octopusden.octopus.escrow.configuration.validation.EscrowModuleConfigMatcher
import org.octopusden.octopus.escrow.dto.ComponentArtifactConfiguration
import org.octopusden.octopus.escrow.exceptions.ComponentResolverException
import org.octopusden.octopus.escrow.exceptions.EscrowConfigurationException
import org.octopusden.octopus.escrow.model.VCSSettings
import org.octopusden.octopus.releng.dto.ComponentVersion
import org.octopusden.octopus.releng.dto.JiraComponent
import org.octopusden.releng.versions.IVersionInfo
import org.octopusden.releng.versions.KotlinVersionFormatter
import org.octopusden.releng.versions.NumericVersion
import groovy.transform.TypeChecked
import org.apache.commons.lang3.Validate
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.maven.artifact.Artifact

@TypeChecked
class JiraParametersResolver implements IJiraParametersResolver {
    public static final KotlinVersionFormatter FORMATTER = new KotlinVersionFormatter()
    Logger log = LogManager.getLogger(JiraParametersResolver.class)

    private EscrowConfigurationLoader escrowConfigurationLoader
    private EscrowConfiguration escrowConfiguration
    private final EscrowModuleConfigMatcher escrowModuleConfigMatcher = new EscrowModuleConfigMatcher()
    private Map<String, String> params

    JiraParametersResolver() {}

    JiraParametersResolver(EscrowConfigurationLoader escrowConfigurationLoader, Map<String, String> params) {
        Objects.requireNonNull(escrowConfigurationLoader)
        this.escrowConfigurationLoader = escrowConfigurationLoader
        this.params = params
    }

    void setEscrowConfiguration(EscrowConfiguration escrowConfiguration) {
        this.escrowConfiguration = escrowConfiguration
    }

    @Override
    void reloadComponentsRegistry() {
        escrowConfiguration = escrowConfigurationLoader.loadFullConfigurationWithoutValidationForUnknownAttributes(params)
    }

    @Override
    Map<String, ComponentArtifactConfiguration> getMavenArtifactParameters(String component) {
        getEscrowModuleConfigs{String moduleName, EscrowModuleConfig escrowModuleConfig -> moduleName == component}.collectEntries {[(it.getVersionRangeString()): new ComponentArtifactConfiguration(it.groupIdPattern, it.artifactIdPattern)]}
    }

    @Override
    JiraComponent resolveComponent(Artifact mavenArtifact) {
        EscrowModuleConfig moduleConfig = getEscrowModuleConfig(mavenArtifact)
        if (moduleConfig == null) {
            throw new ComponentResolverException("Failed to resolve artifact $mavenArtifact in Escrow Config")
        }
        return getJiraComponentParameters(moduleConfig)
    }

    @Override
    ComponentVersion getComponentByMavenArtifact(Artifact mavenArtifact) {
        ComponentVersion componentRelease = null
        escrowConfiguration.escrowModules.each { String componentName, EscrowModule escrowModule ->
            escrowModule.moduleConfigurations.each { EscrowModuleConfig moduleConfig ->
                if (escrowModuleConfigMatcher.match(mavenArtifact, moduleConfig)) {
                    componentRelease = ComponentVersion.create(componentName, mavenArtifact.getVersion());
                }
            }
        }
        return componentRelease
    }

    @Override
    JiraComponent resolveComponent(ComponentVersion componentRelease) {
        def moduleConfig = EscrowConfigurationLoader.getEscrowModuleConfig(escrowConfiguration, componentRelease)
        if (moduleConfig == null) {
            throw new ComponentResolverException("Failed to resolve artifact $componentRelease in Escrow Config")
        }
        return getJiraComponentParameters(moduleConfig)
    }

    private static JiraComponent getJiraComponentParameters(EscrowModuleConfig configuration) {
        assert configuration != null
        JiraComponent componentParameters = configuration.getJiraConfiguration()
        assert componentParameters != null
        return componentParameters
    }

    @Override
    boolean isComponentWithJiraParametersExists(Artifact mavenArtifact) {
        def acceptClosure = getMavenArtifactClosure(mavenArtifact)

        def configs = getEscrowModuleConfigs(acceptClosure)
        if (configs.size() == 0) {
            return false
        }

        boolean anyEscrowModuleConfigContainsJiraSection = false
        configs.each { moduleConfig ->
            if (jiraConfigurationFromModuleConfiguration(moduleConfig, mavenArtifact)) {
                anyEscrowModuleConfigContainsJiraSection = true
            }
        }

        return anyEscrowModuleConfigContainsJiraSection
    }

    @Override
    boolean isComponentWithJiraParametersExists(ComponentVersion componentRelease) {
        def moduleConfig = EscrowConfigurationLoader.getEscrowModuleConfig(escrowConfiguration, componentRelease)
        if (moduleConfig == null) {
            return false
        }
        return jiraConfigurationFromModuleConfiguration(moduleConfig, moduleConfig)
    }

    @Override
    ComponentVersion getComponentByJiraProject(JiraProjectVersion jiraProjectVersion) {
        Validate.notNull(jiraProjectVersion)
        String foundComponent = null;
        escrowConfiguration.escrowModules.each { String componentName, EscrowModule escrowModule ->
            escrowModule.moduleConfigurations.each { EscrowModuleConfig escrowModuleConfig ->
                if (matchesComponentByProjectKeyAndVersion(escrowModuleConfig, jiraProjectVersion)) {
                    foundComponent = componentName;
                }
            }
        }
        return foundComponent != null ? ComponentVersion.create(foundComponent, jiraProjectVersion.version) : null
    }

    @Override
    ComponentConfig getComponentConfig() {
        Map<String, List<JiraComponentVersionRange>> projectKeyToJiraComponentVersionRangeMap = [:]
        Map<String, List<JiraComponentVersionRange>> componentNameToJiraComponentVersionRangeMap = [:]

        escrowConfiguration.escrowModules.each { String componentName, EscrowModule escrowModule ->
            escrowModule.moduleConfigurations.each { EscrowModuleConfig escrowModuleConfig ->
                String projectKey = escrowModuleConfig?.jiraConfiguration?.projectKey
                def jc = new ModelConfigPostProcessor(ComponentVersion.create(componentName, "")).resolveJiraConfiguration(escrowModuleConfig.jiraConfiguration)
                def enrichedModuleConfig = new EscrowModuleConfig(
                    buildSystem: escrowModuleConfig.buildSystem,
                    artifactIdPattern: escrowModuleConfig.artifactIdPattern,
                    groupIdPattern: escrowModuleConfig.groupIdPattern,
                    versionRange: escrowModuleConfig.versionRangeString,
                    buildFilePath: escrowModuleConfig.buildFilePath,
                    jiraConfiguration: jc,
                    buildConfiguration: escrowModuleConfig.buildConfiguration,
                    deprecated: escrowModuleConfig.deprecated,
                    vcsSettings: escrowModuleConfig.vcsSettings,
                    distribution: escrowModuleConfig.distribution,
                    componentDisplayName: escrowModuleConfig.componentDisplayName,
                    componentOwner: escrowModuleConfig.componentOwner,
                    releaseManager: escrowModuleConfig.releaseManager,
                    securityChampion: escrowModuleConfig.securityChampion,
                    system: escrowModuleConfig.system
                )
                addJiraComponentVersionRange(projectKey, projectKeyToJiraComponentVersionRangeMap, enrichedModuleConfig, projectKey, componentName)
                addJiraComponentVersionRange(componentName, componentNameToJiraComponentVersionRangeMap, enrichedModuleConfig, projectKey, componentName)
            }
        }

        return new ComponentConfig(projectKeyToJiraComponentVersionRangeMap, componentNameToJiraComponentVersionRangeMap);
    }

    private
    static void addJiraComponentVersionRange(String key, Map<String, List<JiraComponentVersionRange>> keyToVersionRangeMap,
                                             EscrowModuleConfig escrowModuleConfig,
                                             String projectKey, String componentName) {
        JiraComponent component = escrowModuleConfig?.jiraConfiguration
        if (projectKey != null && component != null && escrowModuleConfig?.jiraConfiguration?.componentVersionFormat != null) {
            def versionRange = new JiraComponentVersionRange(componentName, escrowModuleConfig.getVersionRangeString(), component,
                    escrowModuleConfig.distribution, escrowModuleConfig.vcsSettings)
            if (keyToVersionRangeMap.containsKey(key)) {
                List<JiraComponentVersionRange> versionRangeList = new ArrayList(keyToVersionRangeMap.get(key))
                versionRangeList.add(versionRange);
                keyToVersionRangeMap.put(key, versionRangeList);
            } else {
                keyToVersionRangeMap.put(key, Arrays.asList(versionRange));
            }
        }
    }

    private static boolean matchesComponentByProjectKeyAndVersion(EscrowModuleConfig escrowModuleConfig,
                                                                  JiraProjectVersion jiraProjectVersion) {
        def jiraConfiguration = escrowModuleConfig.jiraConfiguration;
        if (jiraProjectVersion?.projectKey == escrowModuleConfig?.jiraConfiguration?.projectKey) {

            def componentVersionFormat = jiraConfiguration.componentVersionFormat
            def releaseVersionFormat = componentVersionFormat.releaseVersionFormat
            def majorVersionFormat = componentVersionFormat.majorVersionFormat
            def buildVersionFormat = componentVersionFormat.buildVersionFormat
            def version = jiraProjectVersion.version

            def componentInfo = jiraConfiguration.componentInfo
            def customerVersionFormat = componentInfo?.versionFormat
            def customerVersionPrefix = componentInfo?.versionPrefix

            if (customerVersionFormat == null || customerVersionPrefix == null) {
                return matches(releaseVersionFormat, version) ||
                    matches(majorVersionFormat, version) ||
                    matches(buildVersionFormat, version)
            } else {
                return matches(customerVersionFormat, releaseVersionFormat, customerVersionPrefix, version) ||
                    matches(customerVersionFormat, majorVersionFormat, customerVersionPrefix, version) ||
                    matches(customerVersionFormat, buildVersionFormat, customerVersionPrefix, version)
            }
        }
    }

    private static boolean matches(String versionFormat, String version) {
        versionFormat != null && FORMATTER.matchesFormat(versionFormat, version)
    }

    private static boolean matches(String customerVersionFormat, String versionFormat, String componentVersionPrefix, String version) {
        versionFormat != null && FORMATTER.matchesFormat(customerVersionFormat, versionFormat, componentVersionPrefix, version)
    }


    @Override
    VCSSettings getVersionControlSystemRootsByJiraProject(JiraProjectVersion jiraProjectVersion) {
        Validate.notNull(jiraProjectVersion)
        String componentName = ""
        VCSSettings vcsSettings
        escrowConfiguration.escrowModules.each { String name, EscrowModule escrowModule ->
            escrowModule.moduleConfigurations.each { EscrowModuleConfig escrowModuleConfig ->
                if (matchesComponentByProjectKeyAndVersion(escrowModuleConfig, jiraProjectVersion)) {
                    final IVersionInfo numericArtifactVersion = NumericVersion.parse(jiraProjectVersion.getVersion())
                    if (escrowModuleConfig.getVersionRange().containsVersion(numericArtifactVersion)) {
                        ModelConfigPostProcessor modelConfigPostProcessor = new ModelConfigPostProcessor(ComponentVersion.create(name, jiraProjectVersion.getVersion()))
                        vcsSettings = modelConfigPostProcessor.resolveVariables(escrowModuleConfig.getVcsSettings())
                        componentName = name
                    }
                }
            }
        }
        if (vcsSettings == null) {
            return VCSSettings.createEmpty();
        }
        return AbstractResolver.createVCSRootWithFormattedBranch(vcsSettings, componentName, jiraProjectVersion.getVersion())
    }

    private boolean jiraConfigurationFromModuleConfiguration(EscrowModuleConfig configuration, def info) {
        def result = configuration.jiraConfiguration != null && configuration.jiraConfiguration.projectKey != null
        if (result) {
            log.info("Jira configuration is found for $info")
        } else {
            log.warn("Jira configuration is NOT found for $info")
        }
        return result
    }


    private EscrowModuleConfig getEscrowModuleConfig(Artifact mavenArtifact) {
        return getEscrowModuleConfig(mavenArtifact.toString(), { String moduleName, EscrowModuleConfig moduleConfig ->
            escrowModuleConfigMatcher.match(mavenArtifact, moduleConfig)
        })
    }

    private Closure getMavenArtifactClosure(Artifact mavenArtifact) {
        return { String moduleName, EscrowModuleConfig moduleConfig ->
            escrowModuleConfigMatcher.match(mavenArtifact, moduleConfig)
        }
    }

    private EscrowModuleConfig getEscrowModuleConfig(String component, Closure accept) {
        List<EscrowModuleConfig> escrowModuleConfigList = getEscrowModuleConfigs(accept)
        if (escrowModuleConfigList.size() == 0) {
            throw new EscrowConfigurationException("Failed to resolve artifact $component in Escrow Config. Now configurations found");
        }
        if (escrowModuleConfigList.size() > 1) {
            throw new EscrowConfigurationException("Failed to resolve component in config. There are more than one matching result for $component")
        }
        return escrowModuleConfigList[0]
    }


    private List<EscrowModuleConfig> getEscrowModuleConfigs(Closure accept) {
        List<EscrowModuleConfig> result = []
        escrowConfiguration.escrowModules.each { String moduleName, EscrowModule escrowModule ->
            escrowModule.moduleConfigurations.each { EscrowModuleConfig escrowModuleConfig ->
                if (accept(moduleName, escrowModuleConfig)) {
                    result.add(escrowModuleConfig)
                }
            }
        }
        return result
    }
}
