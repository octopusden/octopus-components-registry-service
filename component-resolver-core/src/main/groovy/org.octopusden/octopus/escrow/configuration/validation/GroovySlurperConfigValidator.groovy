package org.octopusden.octopus.escrow.configuration.validation

import org.octopusden.octopus.escrow.dto.EscrowExpressionContext
import org.octopusden.octopus.escrow.dto.MavenArtifactDistributionEntity
import org.octopusden.octopus.escrow.exceptions.EscrowConfigurationException
import org.octopusden.octopus.escrow.resolvers.ReleaseInfoResolver
import org.octopusden.octopus.escrow.utilities.DistributionUtilities
import org.octopusden.octopus.escrow.utilities.EscrowExpressionParser

import java.util.regex.Pattern

class GroovySlurperConfigValidator {

    public static final String JIRA = 'jira'
    public static final String VCS_SETTINGS = 'vcsSettings'
    public static final String BUILD = 'build'
    public static final String CUSTOMER = 'customer'
    public static final String TOOLS = "Tools"
    public static final String COMPONENT = 'component'
    public static final String DISTRIBUTION = 'distribution'
    public static final String DEPENDENCIES = 'dependencies'
    public static final String SECURITY_GROUPS = 'securityGroups'
    public static final String SECURITY_GROUPS_READ = "read"
    private static final String GAV_PROHIBITED_SYMBOLS = ":,\\s/"
    private static final String GAV_FILE_PATTERN ="(file:)(/|/{3})([^/\\\\0,]+(/)?)+"
    private static final String GAV_MAVEN_PATTERN =String.format("[^%1\$s]+(:[^%1\$s]+){1,3}", GAV_PROHIBITED_SYMBOLS)
    private static final String GAV_ENTRY_PATTERN = "($GAV_FILE_PATTERN)|($GAV_MAVEN_PATTERN)"
    public static final Pattern GAV_PATTERN = Pattern.compile("^($GAV_ENTRY_PATTERN)(,($GAV_ENTRY_PATTERN))*\$")
    private static final String SG_ENTRY_REGEX = "[\\w-#\\s]+"
    public static final Pattern SECURITY_GROUPS_PATTERN = Pattern.compile("^($SG_ENTRY_REGEX)(,($SG_ENTRY_REGEX))*\$")

    public
    static SUPPORTED_ATTRIBUTES = ['buildSystem', VCS_URL, REPOSITORY_TYPE, 'groupId', 'artifactId',
                                   TAG, 'versionRange', 'version', 'module',
                                   'teamcityReleaseConfigId', 'jiraProjectKey', 'jiraMajorVersionFormat', 'jiraReleaseVersionFormat',
                                   'buildFilePath', 'deprecated', BRANCH,
                                   'componentDisplayName', 'componentOwner', 'releaseManager', 'securityChampion', 'system', 'octopusVersion']
    static SUPPORTED_JIRA_ATTRIBUTES = ['projectKey', 'lineVersionFormat', 'majorVersionFormat', 'releaseVersionFormat', 'buildVersionFormat', "displayName", 'technical'];

    static SUPPORTED_BUILD_ATTRIBUTES = ['dependencies', 'javaVersion', 'mavenVersion', 'gradleVersion', 'requiredProject', 'systemProperties', 'projectVersion', 'requiredTools', 'buildTasks']

    static SUPPORTED_COMPONENT_ATTRIBUTES = ['versionPrefix', 'versionFormat']

    static SUPPORTED_VCS_ATTRIBUTES = [BRANCH, TAG, REPOSITORY_TYPE, VCS_URL, EXTERNAL_REGISTRY]

    static SUPPORTED_TOOLS_ATTRIBUTES = ['escrowEnvironmentVariable', 'sourceLocation', 'targetLocation', 'installScript']

    static SUPPORTED_DISTRIBUTION_ATTRIBUTES = ['external', 'explicit', 'GAV', 'securityGroups']
    static SUPPORTED_DEPENDENCIES_ATTRIBUTES = ['autoUpdate']
    static SUPPORTED_SECURITY_GROUPS_ATTRIBUTES = [SECURITY_GROUPS_READ]

    public static final String BRANCH = 'branch'
    public static final String TAG = 'tag'
    public static final String REPOSITORY_TYPE = 'repositoryType'
    public static final String VCS_URL = 'vcsUrl'
    public static final String EXTERNAL_REGISTRY = "externalRegistry"

    List<String> errors = new ArrayList<>();

    void validateConfig(ConfigObject rootObject) {
        validateAttributes(rootObject)
        if (hasErrors()) {
            StringBuilder errorBuff = new StringBuilder();
            getErrors().each {
                errorBuff.append "\n$it";
            }
            throw new EscrowConfigurationException("Validation of module config failed due to following errors: ${errorBuff.toString()}");
        }
    }

    private boolean validateAttributes(ConfigObject rootObject) {
        for (Map.Entry entry in rootObject) {
            String moduleName = entry.key as String
            if (ReleaseInfoResolver.TOOLS_SETTINGS == moduleName) {
                validateTools(entry.value as ConfigObject)
            } else if (ReleaseInfoResolver.DEFAULT_SETTINGS == moduleName) {
                validateConfigSectionForUnknownAttributes(entry, moduleName)
            } else if (ReleaseInfoResolver.DEFAULT_SETTINGS != moduleName || ReleaseInfoResolver.TOOLS_SETTINGS != moduleName) {
                if (entry.value instanceof ConfigObject) {
                    ConfigObject moduleConfigObject = entry.value as ConfigObject
                    validateComponent(moduleConfigObject, moduleName)
                }
            }
        }
        return hasErrors()

    }

    private void validateComponent(ConfigObject moduleConfigObject, String componentName) {
        if (!moduleConfigObject.isEmpty()) {
            for (configTypeObject in moduleConfigObject) {
                def attribute = configTypeObject.key
                if (SUPPORTED_ATTRIBUTES.contains(attribute)) {
                    if (configTypeObject.value instanceof ConfigObject) {
                        registerError("Incorrect value of attribute $attribute in module $componentName. String expected");
                    }
                } else if (attribute == JIRA) {
                    validateJiraParameters(moduleConfigObject, "defaults", componentName)
                } else if (attribute == VCS_SETTINGS) {
                    validateVCSParameters(moduleConfigObject, attribute as String, componentName)
                } else if (attribute == BUILD) {
                    validateBuildParameters(moduleConfigObject, "defaults", componentName)
                } else if (attribute == DISTRIBUTION) {
                    validateDistributionParameters(moduleConfigObject, "defaults", componentName)
                } else if (attribute == DEPENDENCIES) {
                    validateDependenciesParameters(moduleConfigObject, "dependencies", componentName)
                } else if (attribute == "components") {
                    validateSubComponents(moduleConfigObject);
                } else {
                    validateConfigSectionForUnknownAttributes(configTypeObject, componentName)
                }
            }
        }
    }

    def validateSubComponents(ConfigObject moduleConfigObject) {
        if (moduleConfigObject.containsKey("components")) {
            def componentsObject = moduleConfigObject.get("components") as ConfigObject
            componentsObject.each { String key, value ->
                if (!value instanceof ConfigObject) {
                    registerError("Incorrect data ($key:$value) in components{} section")
                    return;
                }
                validateComponent(value as ConfigObject, key)
            }
        }
    }

    def validateTools(ConfigObject tools) {
        tools.each { String key, value ->
            if (!value instanceof ConfigObject) {
                registerError("Incorrect data ($key:$value) in components{} section")
                return;
            }
            validateToolSection(value as ConfigObject, key)
        }

    }

    private void validateToolSection(ConfigObject moduleConfigObject, String moduleName) {
        if (!moduleConfigObject.isEmpty()) {
            for (vcsTypeObject in moduleConfigObject) {
                def attribute = vcsTypeObject.key
                if (SUPPORTED_TOOLS_ATTRIBUTES.contains(attribute)) {
                    if (vcsTypeObject.value instanceof ConfigObject) {
                        registerError("Incorrect value of attribute $attribute in module $moduleName. String expected");
                    }
                } else {
                    registerError("Unsupported attribute '$attribute' in component $moduleName");
                }
            }
        }
    }

    private void validateConfigSectionForUnknownAttributes(Map.Entry<Object, Object> configTypeObject,
                                                           String componentName) {
        String moduleConfigName = configTypeObject.key as String
        if (!(configTypeObject.value instanceof ConfigObject)) {
            registerError("Unsupported attribute '$moduleConfigName' in component $componentName");
            return;
        }
        ConfigObject configObject = configTypeObject.value as ConfigObject

        configObject.each() { key, value ->
            if (key == BUILD) {
                validateBuildParameters(configObject, moduleConfigName, componentName)
            } else if (key == JIRA) {
                validateJiraParameters(configObject, moduleConfigName, componentName)
            } else if (key == DISTRIBUTION) {
                validateDistributionParameters(configObject, moduleConfigName, componentName)
            } else if (key == VCS_SETTINGS) {
                validateVCSSettingsSection(configObject, componentName, key as String)
            } else if (!SUPPORTED_ATTRIBUTES.contains(key)) {
                registerError("Unknown attribute '$key' in " +
                        getWhereMessage(moduleConfigName, componentName));
            }
        }
    }


    private void validateBuildParameters(ConfigObject configObject, String moduleConfigName, String moduleName) {
        def buildSectionValue = configObject.get(BUILD)
        if (buildSectionValue instanceof ConfigObject) {
            validateBuildSection(buildSectionValue as ConfigObject, moduleName, moduleConfigName)
        } else {
            registerError("Build section is not correctly configured in " +
                    getWhereMessage(moduleConfigName, moduleName));
        }
    }

    private void validateDistributionParameters(ConfigObject configObject, String moduleConfigName, String moduleName) {
        def distributionSectionValue = configObject.get(DISTRIBUTION)
        if (distributionSectionValue instanceof ConfigObject) {
            validateDistributionSection(distributionSectionValue as ConfigObject, moduleName, moduleConfigName)
        } else {
            registerError("Distribution section is not correctly configured in " +
                    getWhereMessage(moduleConfigName, moduleName));
        }
    }

    private void validateSecurityGroupsParameters(ConfigObject configObject, String moduleConfigName, String moduleName) {
        def securityGroupsSectionValue = configObject.get(SECURITY_GROUPS)
        if (securityGroupsSectionValue instanceof ConfigObject) {
            validateSecurityGroupsSection(securityGroupsSectionValue as ConfigObject, moduleName, moduleConfigName)
        } else {
            registerError("Security Groups section is not correctly configured in " +
                    getWhereMessage(moduleConfigName, moduleName));
        }
    }

    private void validateDependenciesParameters(ConfigObject configObject, String moduleConfigName, String moduleName) {
        def dependenciesSectionValue = configObject.get(DEPENDENCIES)
        if (dependenciesSectionValue instanceof ConfigObject) {
            validateDependenciesSection(dependenciesSectionValue as ConfigObject, moduleName, moduleConfigName)
        } else {
            registerError("Dependencies section is not correctly configured in " +
                    getWhereMessage(moduleConfigName, moduleName));
        }
    }

    def validateDistributionSection(ConfigObject distributionSection, String moduleName, String moduleConfigName) {
        validateForUnknownAttributes(distributionSection, DISTRIBUTION, SUPPORTED_DISTRIBUTION_ATTRIBUTES, moduleName, moduleConfigName)
        if (distributionSection.containsKey("GAV")) {
            try {
                def gavValue = EscrowExpressionParser.getInstance().parseAndEvaluate(distributionSection.get("GAV") as String, EscrowExpressionContext.validationEscrowExpressionContext)
                try {
                    DistributionUtilities.parseDistributionGAV(gavValue).forEach{ distributionItem ->
                        if (distributionItem instanceof MavenArtifactDistributionEntity) {
                            if (!GAV_PATTERN.matcher(distributionItem.gav).matches()) {
                                registerError("GAV '${distributionItem.gav}' must match pattern '$GAV_PATTERN'")
                            }
                        }
                    }
                } catch(Exception parsingException) {
                    registerError("Fail to parse GAV: " + parsingException)
                }
            } catch (Exception exception) {
                registerError("GAV expression is not valid: " + exception.getMessage())
            }
        }
        if (distributionSection.containsKey(SECURITY_GROUPS)) {
            validateSecurityGroupsParameters(distributionSection, SECURITY_GROUPS, moduleName)
        }
    }

    def validateDependenciesSection(ConfigObject distributionSection, String moduleName, String moduleConfigName) {
        validateForUnknownAttributes(distributionSection, DEPENDENCIES, SUPPORTED_DEPENDENCIES_ATTRIBUTES, moduleName, moduleConfigName)
    }

    def validateBuildSection(ConfigObject buildSection, String moduleName, String moduleConfigName) {
        validateForUnknownAttributes(buildSection, BUILD, SUPPORTED_BUILD_ATTRIBUTES, moduleName, moduleConfigName)
    }

    private validateForUnknownAttributes(ConfigObject section, String sectionName, List<String> supportedAttributes, String moduleName, String moduleConfigName) {
        section.keySet().each {
            if (!supportedAttributes.contains(it)) {
                registerError("Unknown $sectionName attribute '$it' in " +
                        getWhereMessage(moduleConfigName, moduleName));
            }
        }
    }

    private void validateJiraParameters(ConfigObject vcsConfigObject, String moduleConfigName, String moduleName) {
        if (vcsConfigObject.containsKey("jiraProjectKey")
                || vcsConfigObject.containsKey("jiraMajorVersionFormat")
                || vcsConfigObject.containsKey("jiraReleaseVersionFormat")) {
            registerError("Ambiguous jira configuration of component '$moduleName' section $moduleConfigName")
        }
        def jiraSectionValue = vcsConfigObject.get("jira")
        if (jiraSectionValue instanceof ConfigObject) {
            validateJiraSection(jiraSectionValue as ConfigObject, moduleName, moduleConfigName)
        } else {
            registerError("JIRA section is not correctly configured in " +
                    getWhereMessage(moduleConfigName, moduleName));
        }
    }

    def validateJiraSection(ConfigObject jiraSection, String moduleName, String moduleConfigName) {
        if (jiraSection.keySet().contains(CUSTOMER) && jiraSection.keySet().contains(COMPONENT)) {
            registerError("jira section could not have both customer/component section in  " +
                    getWhereMessage(moduleConfigName, moduleName))
        }
        jiraSection.keySet().each {
            if (it == CUSTOMER || it == COMPONENT) {
                validateCustomParameters(jiraSection.get(it) as ConfigObject, "defaults", moduleName)
            } else if (!SUPPORTED_JIRA_ATTRIBUTES.contains(it)) {
                registerError("Unknown jira attribute '$it' in " + getWhereMessage(moduleConfigName, moduleName));
            }
        }
    }

    def validateVCSParameters(ConfigObject moduleConfigObject, String moduleConfigName, String componentName) {
        if (moduleConfigObject.containsKey("vcsUrl")
                || moduleConfigObject.containsKey("repositoryType")
                || moduleConfigObject.containsKey("branch")) {
            registerError("Ambiguous VCS configuration of component '$componentName' section $moduleConfigName")
        }

        def vcsSettingsObject = moduleConfigObject.get(VCS_SETTINGS)
        if (vcsSettingsObject instanceof ConfigObject) {
            validateVCSSettingsSection(moduleConfigObject as ConfigObject, moduleConfigName, componentName)
        } else {
            registerError("$VCS_SETTINGS section is not correctly configured in " +
                    getWhereMessage(moduleConfigName, componentName));
        }
    }

    def validateVCSSettingsSection(ConfigObject configObject, String moduleConfigName, String componentName) {
        def vcsConfigObject = configObject.get(VCS_SETTINGS)
        if (vcsConfigObject instanceof ConfigObject) {
            vcsConfigObject.keySet().each { String key ->
                def valueObject = vcsConfigObject.get(key)
                if (valueObject instanceof ConfigObject) {
                    ConfigObject vcsRootObject = valueObject as ConfigObject
                    validateVCSRoot(vcsRootObject, key, componentName + "->" + moduleConfigName)
                } else if (!SUPPORTED_VCS_ATTRIBUTES.contains(key)) {
                    registerError("Unknown '$key' attribute in " + getWhereMessage(moduleConfigName, componentName));
                }
            }
        } else {
            registerError("VCS Settings section is not correctly configured in " +
                    getWhereMessage(moduleConfigName, componentName));
        }
    }

    def validateSecurityGroupsSection(ConfigObject securityGroupsConfigObject, String moduleConfigName, String moduleName) {
        validateForUnknownAttributes(securityGroupsConfigObject as ConfigObject, SECURITY_GROUPS, SUPPORTED_SECURITY_GROUPS_ATTRIBUTES, moduleName, moduleConfigName)
        if (securityGroupsConfigObject.containsKey(SECURITY_GROUPS_READ)) {
            def read = securityGroupsConfigObject.get(SECURITY_GROUPS_READ) as String
            if (!SECURITY_GROUPS_PATTERN.matcher(read).matches()) {
                registerError("Security Groups is not correctly configured in ${moduleConfigName}. '$read' does not match ${SECURITY_GROUPS_PATTERN.pattern()}")
            }
        }
    }

    def validateVCSRoot(ConfigObject vcsRootObject, String configSectionName, String componentName) {
        vcsRootObject.keySet().each { String key ->
            def valueObject = vcsRootObject.get(key)
            if (valueObject instanceof ConfigObject) {
                registerError("VCS Root is not correctly configured in " + getWhereMessage(componentName, configSectionName))
            } else if (!SUPPORTED_VCS_ATTRIBUTES.contains(key)) {
                registerError("Unknown '$key' attribute in " + getWhereMessage(componentName, configSectionName));
            }
        }
    }

    private static GString getWhereMessage(String moduleConfigName, String componentName) {
        "${moduleConfigName == ReleaseInfoResolver.DEFAULT_SETTINGS ? ReleaseInfoResolver.DEFAULT_SETTINGS : componentName + '->' + moduleConfigName} section of escrow config file\""
    }

    private void validateCustomParameters(ConfigObject customerSectionValue, String moduleConfigName, String moduleName) {
        if (customerSectionValue instanceof ConfigObject) {
            validateComponentSection(customerSectionValue as ConfigObject, moduleName, moduleConfigName)
        } else {
            registerError("Customer/Component section is not correctly configured in " +
                    getWhereMessage(moduleConfigName, moduleName));
        }
    }

    private void validateComponentSection(ConfigObject customerSection, String moduleName, String moduleConfigName) {
        validateForUnknownAttributes(customerSection, "customer/component", SUPPORTED_COMPONENT_ATTRIBUTES, moduleName, moduleConfigName)
    }

    void registerError(String message) {
        errors.add(message)
    }

    boolean hasErrors() {
        return !errors.isEmpty();
    }

    List<String> getErrors() {
        return errors
    }


}
