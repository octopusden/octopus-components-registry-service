package org.octopusden.octopus.escrow.configuration.validation

import org.octopusden.octopus.escrow.BuildSystem
import org.octopusden.octopus.escrow.MavenArtifactMatcher
import org.octopusden.octopus.escrow.RepositoryType
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.octopus.escrow.configuration.model.EscrowConfiguration
import org.octopusden.octopus.escrow.configuration.model.EscrowModule
import org.octopusden.octopus.escrow.configuration.model.EscrowModuleConfig
import org.octopusden.octopus.escrow.configuration.validation.util.VersionRangeHelper
import org.octopusden.octopus.escrow.model.VersionControlSystemRoot
import org.octopusden.releng.versions.KotlinVersionFormatter
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.VersionRange
import groovy.transform.TupleConstructor
import groovy.transform.TypeChecked
import kotlin.Pair
import org.apache.commons.lang3.StringUtils
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.octopusden.releng.versions.VersionRangeFactory

import java.util.function.BinaryOperator
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import java.util.stream.Stream

@TypeChecked
class EscrowConfigValidator {

    static VersionRangeHelper versionRangeHelper = new VersionRangeHelper()
    static MavenArtifactMatcher mavenArtifactMatcher = new MavenArtifactMatcher()

    private static final Logger LOG = LogManager.getLogger(EscrowConfigValidator.class)
    public static final String SPLIT_PATTERN = "[,|\\s]+"

    private List<String> supportedGroupIds
    private List<String> supportedSystems
    private VersionNames versionNames

    @TupleConstructor
    static class MavenArtifact {
        String groupId
        String artifactId
        String componentName
        VersionRange versionRange

        boolean equals(o) {
            if (this.is(o)) return true
            if (getClass() != o.class) return false

            MavenArtifact that = (MavenArtifact) o

            if (artifactId != that.artifactId) return false
            if (groupId != that.groupId) return false

            return true
        }

        int hashCode() {
            int result
            result = groupId.hashCode()
            result = 31 * result + artifactId.hashCode()
            return result
        }

        @Override
        String toString() {
            return "$groupId:$artifactId"
        }
    }

    Map<MavenArtifact, List<EscrowModuleConfig>> map = new HashMap<>()

    EscrowConfigValidator(List<String> supportedGroupIds, List<String> supportedSystems, VersionNames versionNames ) {
        this.supportedGroupIds = supportedGroupIds
        this.supportedSystems = supportedSystems
        this.versionNames = versionNames
    }

    List<String> errors = new ArrayList<>()

    boolean validateEscrowConfiguration(EscrowConfiguration configuration) {
        LOG.info("Validate of escrow configuration started")
        def modules = configuration.getEscrowModules()
        for (String component : modules.keySet()) {
            EscrowModule escrowModule = configuration.getEscrowModules().get(component)
            def configurations = escrowModule.getModuleConfigurations()
            if (configurations.isEmpty()) {
                registerError("No configurations in module $component")
            }
            for (EscrowModuleConfig moduleConfig : configurations) {
                validateMandatoryFields(moduleConfig, component)
                validateBuildSystem(moduleConfig.getBuildSystem(), component)
                validateArtifactId(moduleConfig.getArtifactIdPattern(), component)
                validateGroupId(moduleConfig, component)
                validateVcsSettings(moduleConfig, component)
                validateVersionRange(moduleConfig, component)
                validateJiraParams(moduleConfig, component)
                validateExplicitExternalComponent(moduleConfig, component)
                validateSystem(moduleConfig, component)
            }
        }
        if (!hasErrors()) {
            validateVersionConflicts(configuration)
            validateGroupIdAndVersionIdIntersections(configuration)
            validateArchivedComponents(configuration)
        } else {
            LOG.warn("Validating version conflicts is skipped due to the previous errors")
        }
        LOG.info("Validate of escrow configuration completed")
        return hasErrors()
    }

    private void validateMandatoryFields(EscrowModuleConfig moduleConfig, String component) {
        if (StringUtils.isBlank(moduleConfig.componentOwner)) {
            registerError("componentOwner is not set in '$component'")
        }
    }

    private void validateExplicitExternalComponent(EscrowModuleConfig moduleConfig, String component) {
        if (moduleConfig.distribution?.explicit() && moduleConfig.distribution?.external()) {
            if (StringUtils.isBlank(moduleConfig.componentDisplayName)) {
                registerError("componentDisplayName is not set in '$component'")
            }
            if (StringUtils.isBlank(moduleConfig.releaseManager)) {
                registerError("releaseManager is not set in '$component'")
            }
            def securityChampions = moduleConfig.securityChampion
            if (StringUtils.isNotBlank(securityChampions)) {
                def userListPattern = "\\w+(,\\w+)*"
                if (securityChampions?.matches(userListPattern) != true) {
                    registerError("securityChampion is not matched '$userListPattern' in '$component'")
                }
            } else {
                registerError("securityChampion is not set in '$component'")
            }
        }
    }

    def validateBuildSystem(BuildSystem buildSystem, String component) {
        if (buildSystem == null) {
            registerError("buildSystem is not specified in '$component'")
        }
    }

    def validateGroupIdAndVersionIdIntersections(EscrowConfiguration configuration) {
        List<MavenArtifact> mavenArtifacts = []
        def versionRangeFactory = new VersionRangeFactory(configuration.versionNames)
        configuration.escrowModules.each { key, value ->
            value.moduleConfigurations.each {moduleConfiguration ->
                moduleConfiguration.artifactIdPattern.split(SPLIT_PATTERN).each { String artifactId ->
                    mavenArtifacts.add(new MavenArtifact(groupId: moduleConfiguration.groupIdPattern,
                            artifactId: artifactId,
                            componentName: value.moduleName,
                            versionRange: versionRangeFactory.create(moduleConfiguration.versionRangeString)))
                }
            }
        }

        for (int i = 0; i < mavenArtifacts.size(); i++) {
            def mavenArtifact1 = mavenArtifacts[i]
            for (int j = i + 1; j < mavenArtifacts.size(); j++) {
                def mavenArtifact2 = mavenArtifacts[j]
                if (mavenArtifact1.componentName != mavenArtifact2.componentName &&
                        versionRangeHelper.hasIntersection(mavenArtifact1.versionRange, mavenArtifact2.versionRange) &&
                        (mavenArtifactContainsAnother(mavenArtifact1, mavenArtifact2) || mavenArtifactContainsAnother(mavenArtifact2, mavenArtifact1))) {
                    registerError("groupId:artifactId patterns of module $mavenArtifact1.componentName has intersection with $mavenArtifact2.componentName")
                }
            }
        }
    }

    /**
     * Validate archived component.
     * Check distribution section of the component.
     * For archived components the {@link org.octopusden.octopus.escrow.model.Distribution} explicit and external mustn't be set to true in the same.
     * @param configuration configuration for validation
     */
    def validateArchivedComponents(EscrowConfiguration configuration) {
        configuration.escrowModules.each { componentKey, value ->
            value.moduleConfigurations.each {config ->
                //TODO Use appropriate attribute to check if component is archived
                if (config?.componentDisplayName?.endsWith("(archived)")) {
                    if (config.distribution.explicit() && config.distribution.external()) {
                        registerError("Archived component '$componentKey' can't be explicitly distributed. Pls set distribution->explicit=false")
                    }
                }
            }
        }
    }

    static boolean mavenArtifactContainsAnother(MavenArtifact mavenArtifact1, MavenArtifact mavenArtifact2) {
        return (mavenArtifactMatcher.groupIdMatches(mavenArtifact1.groupId, mavenArtifact2.groupId) &&
                mavenArtifactMatcher.artifactIdMatches(mavenArtifact1.artifactId, mavenArtifact2.artifactId))
    }

    def validateVersionConflicts(EscrowConfiguration escrowConfiguration) {
        for (escrowModuleEntry in escrowConfiguration.escrowModules) {
            Map.Entry entry = escrowModuleEntry as Map.Entry
            EscrowModule escrowModule = entry.getValue() as EscrowModule
            for (moduleConfig in escrowModule.moduleConfigurations) {
                def groupIdPattern = moduleConfig.getGroupIdPattern()
                if (groupIdPattern != null) {
                    def groupIdItems = groupIdPattern.split(",")
                    groupIdItems.each { String groupIdItem ->
                        def artifactIdPattern = moduleConfig.getArtifactIdPattern()
                        if (artifactIdPattern != null) {
                            def artifactIdItems = artifactIdPattern.split(SPLIT_PATTERN)
                            artifactIdItems.each { String artifactIdItem ->
                                def mavenArtifact = new MavenArtifact(groupId: groupIdItem, artifactId: artifactIdItem)
                                def escrowModuleConfigs = map.get(mavenArtifact)
                                if (escrowModuleConfigs == null) {
                                    escrowModuleConfigs = new ArrayList<EscrowModuleConfig>()
                                    map.put(mavenArtifact, escrowModuleConfigs)
                                }
                                escrowModuleConfigs.add(moduleConfig)
                            }
                        }
                    }
                }
            }
        }
        def versionRangeFactory = new VersionRangeFactory(escrowConfiguration.versionNames)
        map.each { it ->
            def mavenArtifact = it.key
            List<EscrowModuleConfig> configs = it.value
            if (configs.size() > 1) {
                for (int i = 0; i < configs.size() - 1; i++) {
                    EscrowModuleConfig config1 = configs[i]
                    for (int j = i + 1; j < configs.size(); j++) {
                        EscrowModuleConfig config2 = configs[j]
                        def vr1 = versionRangeFactory.create(config1.getVersionRangeString())
                        def vr2 = versionRangeFactory.create(config2.getVersionRangeString())
                        if (versionRangeHelper.hasIntersection(vr1, vr2)) {
                            registerError("More than one configuration matches $mavenArtifact. " +
                                    "Intersection of version ranges ${config1.getVersionRangeString()} with ${config2.getVersionRangeString()}.")
                        }
                    }
                }
            }
        }
    }

    private void validateArtifactId(String artifactId, String module) {
        if (StringUtils.isEmpty(artifactId)) {
            registerError("artifactId pattern is empty in configuration of module $module")
        } else {
            try {
                Pattern.compile(artifactId)
            } catch (PatternSyntaxException ignored) {
                registerError("artifactId is not valid regular expression  in configuration of module $module")
            }
        }
    }


    def validateGroupId(EscrowModuleConfig moduleConfig, String module) {
        def groupId = moduleConfig.getGroupIdPattern()
        if (StringUtils.isEmpty(groupId)) {  //TODO!
            if (moduleConfig.getBuildSystem() != BuildSystem.BS2_0 && moduleConfig.getBuildSystem() != BuildSystem.ESCROW_NOT_SUPPORTED) {
                registerError("empty groupId is not allowed in configuration of module $module (type=$moduleConfig.buildSystem)")
            }
        } else {
            def items = groupId.split("[,|]")
            for (String item : items) {
                if (!supportedGroupIds.any { item.startsWith(it) }) {
                    registerError("Invalid groupId in configuration of module $module: $item doesn't starts with one of (${supportedGroupIds.join(", ")})")
                }
            }
        }
    }

    def validateVcsSettings(EscrowModuleConfig moduleConfig, String component) {
        if (!(moduleConfig.getBuildSystem() in [BuildSystem.ESCROW_PROVIDED_MANUALLY, BuildSystem.ESCROW_NOT_SUPPORTED, BuildSystem.PROVIDED, BuildSystem.WHISKEY]) &&
                moduleConfig.getVcsSettings().getVersionControlSystemRoots().isEmpty()) {
            registerError("No VCS roots is configured for component '$component' (type=$moduleConfig.buildSystem)")
            return
        }

        def vcsRoots = moduleConfig.getVcsSettings().getVersionControlSystemRoots()
        vcsRoots.each { VersionControlSystemRoot vcsRoot ->
            if (!(moduleConfig.buildSystem == BuildSystem.BS2_0 || moduleConfig.buildSystem == BuildSystem.PROVIDED || moduleConfig.buildSystem == BuildSystem.ESCROW_PROVIDED_MANUALLY) && StringUtils.isEmpty(vcsRoot.vcsPath)) {
                registerError("empty vcsUrl is not allowed in configuration of component $component (type=$moduleConfig.buildSystem)")
            }
            if (vcsRoot.repositoryType == RepositoryType.GIT && vcsRoot.vcsPath.startsWith("ssh://git@bitbucket") && vcsRoot.vcsPath != vcsRoot.vcsPath.toLowerCase()) {
                registerError("component $component git's VCS URL contains upper case char(s): ${vcsRoot.vcsPath}")
            }
        }
        if (moduleConfig.getBuildSystem() == BuildSystem.BS2_0) {
            if (vcsRoots.size() > 1) {
                registerError("Several VCS Roots are not allowed for component '$component' type=${moduleConfig.getBuildSystem()}")
            } else {
                def vcsRoot = moduleConfig.getVcsSettings().getSingleVCSRoot()
                if (vcsRoot.getVcsPath() != EscrowConfigurationLoader.FAKE_VCS_URL_FOR_BS20) {
                    registerError("vcsUrl must be empty for component '$component' type=${moduleConfig.getBuildSystem()}")
                }
            }
        }
    }

    def validateVersionRange(EscrowModuleConfig moduleConfig, String module) {
        if (moduleConfig.getVersionRangeString() == null || moduleConfig.getVersionRangeString().isEmpty()) {
            registerError("Version range in module '$module' isn't set")
        } else {
            try {
                def factory = new VersionRangeFactory(versionNames)
                factory.create(moduleConfig.getVersionRangeString())
            } catch (exception) {
                LOG.error("Module $module validation error", exception)
                registerError("Version range '${moduleConfig.getVersionRangeString()}' in module '$module' doesn't satisfy version range syntax/rules: " + exception.message)
            }
        }
    }
    def validateJiraParams(EscrowModuleConfig moduleConfig, String module) {
        def jiraComponentParameters = moduleConfig.getJiraConfiguration()
        if (jiraComponentParameters == null) {
            registerError("jira section not configured in module '$module'")
            return
        }
        if (StringUtils.isBlank(jiraComponentParameters.getProjectKey())) {
            registerError("projectKey is not specified in module '$module'")
        }
        def componentVersionFormat = jiraComponentParameters.componentVersionFormat
        checkVersionFormat("majorVersionFormat", componentVersionFormat.majorVersionFormat, true, module)
        checkVersionFormat("releaseVersionFormat", componentVersionFormat.releaseVersionFormat, true, module)
        if (componentVersionFormat.releaseVersionFormat == componentVersionFormat.majorVersionFormat) {
            registerError("releaseVersionFormat is same as majorVersionFormat in component '$module'")
        }
        checkVersionFormat("buildVersionFormat", componentVersionFormat.buildVersionFormat, false, module)
        checkVersionFormat("lineVersionFormat", componentVersionFormat.lineVersionFormat, false, module)
    }

    void checkVersionFormat(final String versionFormatName, final String versionFormat, final boolean errorIfNotSet, final String module) {
        if (versionFormat == null) {
            if (errorIfNotSet) {
                registerError("Jira section is not fully configured ($versionFormatName is not set) in module '$module'")
            }
        } else {
            def finishedString = Stream.of(
                    new KotlinVersionFormatter(versionNames)
                    .getPREDEFINED_VARIABLES_LIST()
                    .stream()
                    .map({ i -> '$' + ((Pair) i).first })
                    .sorted { String o1, String o2 -> o2.size() - o1.size() }, [".", "-"].stream())
                .flatMap({ c -> c })
                .reduce (versionFormat, (BinaryOperator<String>) { String s1, String s2 -> s1.replace(s2, "") })

            if (finishedString.size() > 0) {
                registerError("${versionFormatName} has illegal character(s): $finishedString in component $module")
            }
        }
    }

    def validateSystem(EscrowModuleConfig moduleConfig, String component) {
        def system = moduleConfig.getSystem()
        if (system == null) {
            return
        }

        def systemPattern = "\\w+(,\\w+)*"
        if (!system.matches(Pattern.compile(systemPattern))) {
            registerError("system is not matched '$systemPattern' in '$component'")
        } else {
            def unsupportedSystems = system?.split(SPLIT_PATTERN)
                    ?.findAll { s -> !supportedSystems.contains(s) }
                    ?.toList() ?: Collections.emptyList()
            if (!unsupportedSystems.isEmpty()) {
                registerError("system contains unsupported values: ${unsupportedSystems.join(",")} in component '$component'")
            }
        }
    }

    /**
     * Validate component name.
     * @param componentName component name
     * @return Return true is validated successully, false otherwise
     */
    static boolean validateComponentName(String componentName) {
        //TODO implement
        return true
    }

    /**
     * Validate component version.
     * @param componentName component version
     * @return Return true is validated successully, false otherwise
     */
    static boolean validateComponentVersion(String componentVersion) {
        //TODO implement
        return true
    }

    void registerError(String message) {
        errors.add(message)
    }

    boolean hasErrors() {
        return !errors.isEmpty()
    }

    List<String> getErrors() {
        return errors
    }
}
