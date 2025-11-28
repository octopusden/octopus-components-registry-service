package org.octopusden.octopus.escrow.configuration.validation

import org.octopusden.employee.client.EmployeeServiceClient
import org.octopusden.employee.client.common.exception.NotFoundException
import org.octopusden.employee.client.impl.ClassicEmployeeServiceClient
import org.octopusden.employee.client.impl.EmployeeServiceClientParametersProvider
import org.octopusden.octopus.components.registry.api.enums.ProductTypes
import org.octopusden.octopus.escrow.configuration.loader.ComponentRegistryInfo
import org.octopusden.octopus.escrow.configuration.loader.ConfigLoader
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.octopus.escrow.configuration.model.EscrowConfiguration
import org.octopusden.octopus.escrow.configuration.model.EscrowModule
import groovyx.net.http.HTTPBuilder
import org.apache.commons.lang3.StringUtils
import org.octopusden.releng.versions.VersionNames
import org.slf4j.LoggerFactory

import static groovyx.net.http.ContentType.TEXT

/**
 * Component Registry Validator that can run in a separate JVM process.
 * Validates component registry configuration including:
 * - Component Owner, Release Manager, Security Champion validation via Employee Service
 * - JIRA components validation
 * - Comparison with production configuration
 */
class ComponentRegistryValidationTask {

    private static final def log = LoggerFactory.getLogger(ComponentRegistryValidationTask.class)
    static final String ARCHIVED_SUFFIX = "(archived)"
    
    String basePath
    String mainConfigFileName
    String jiraHost
    boolean employeeServiceEnabled
    String employeeServiceUrl
    String employeeServiceUsername
    String employeeServicePassword
    String employeeServiceToken
    String productionConfigPath
    String supportedGroupIds
    String supportedSystems
    String serviceBranch
    String service
    String minor
    String productTypeC
    String productTypeK
    String productTypeD
    String productTypeDDB

    static void main(String[] args) {
        try {
            log.info("=== Component Registry Validation Starting ===")
            log.info("Java version: ${System.getProperty("java.version")}")
            log.info("Working directory: ${System.getProperty("user.dir")}")
            
            // Create instance and load configuration from system properties
            def task = new ComponentRegistryValidationTask()
            task.basePath = getRequiredProperty("cr.basePath")
            task.mainConfigFileName = getRequiredProperty("cr.mainConfigFileName")
            task.jiraHost = getProperty("cr.jiraHost")
            task.employeeServiceEnabled = Boolean.parseBoolean(getProperty("cr.employeeServiceEnabled") ?: "false")
            task.employeeServiceUrl = getProperty("cr.employeeServiceUrl")
            task.employeeServiceUsername = getProperty("cr.employeeServiceUsername")
            task.employeeServicePassword = getProperty("cr.employeeServicePassword")
            task.employeeServiceToken = getProperty("cr.employeeServiceToken")
            task.productionConfigPath = getProperty("cr.productionConfigPath")
            task.supportedGroupIds = getRequiredProperty("cr.supportedGroupIds")
            task.supportedSystems = getRequiredProperty("cr.supportedSystems")
            task.serviceBranch = getRequiredProperty("cr.serviceBranch")
            task.service = getRequiredProperty("cr.service")
            task.minor = getRequiredProperty("cr.minor")
            task.productTypeC = getRequiredProperty("cr.productTypeC")
            task.productTypeK = getRequiredProperty("cr.productTypeK")
            task.productTypeD = getRequiredProperty("cr.productTypeD")
            task.productTypeDDB = getRequiredProperty("cr.productTypeDDB")
            
            log.info("\nConfiguration:")
            log.info("  basePath: $task.basePath")
            log.info("  mainConfigFileName: $task.mainConfigFileName")
            log.info("  jiraHost: $task.jiraHost")
            log.info("  employeeServiceEnabled: $task.employeeServiceEnabled")
            log.info("  employeeServiceUrl: $task.employeeServiceUrl")
            log.info("  productionConfigPath: $task.productionConfigPath")
            log.info("  supportedGroupIds: $task.supportedGroupIds")
            log.info("  supportedSystems: $task.supportedSystems")
            log.info("  serviceBranch: $task.serviceBranch")
            log.info("  service: $task.service")
            log.info("  minor: $task.minor")
            
            // Run validation
            task.runEscrow()
            
            log.info("=== Component Registry Validation Completed Successfully ===")
            System.exit(0)
            
        } catch (Exception e) {
            log.error("=== Component Registry Validation Failed ===", e)
            System.exit(1)
        }
    }

    def runEscrow() {
        log.info "user.dir = ${System.getProperty('user.dir')} \n" +
                "basePath=$basePath \n" +
                "mainConfigFileName=$mainConfigFileName \n" +
                "employeeServiceEnabled=$employeeServiceEnabled \n" +
                "employeeServiceUrl=$employeeServiceUrl \n" +
                "jiraHost=$jiraHost \n" +
                "prodConfigPath=$productionConfigPath \n" +
                "supportedGroupIds=$supportedGroupIds"

        def productTypeMap = new EnumMap(ProductTypes.class)
        productTypeMap.put(ProductTypes.PT_C, productTypeC)
        productTypeMap.put(ProductTypes.PT_K, productTypeK)
        productTypeMap.put(ProductTypes.PT_D, productTypeD)
        productTypeMap.put(ProductTypes.PT_D_DB, productTypeDDB)

        def oldComponents = productionConfigPath 
            ? getComponentsFromConfig(productionConfigPath, productTypeMap)
            : null
        def newComponents = getComponentsFromConfig(basePath, productTypeMap)

        def oldComponentNames = oldComponents?.keySet()
        def newComponentNames = newComponents.keySet()

        final Map<String, Set<String>> ownerComponents = new HashMap<>()
        final Map<String, Set<String>> releaseManagerComponents = new HashMap<>()
        final Map<String, Set<String>> securityChampionComponents = new HashMap<>()

        newComponents.each { componentName, component ->
            component.moduleConfigurations
                    .each { moduleConfiguration ->
                        if (!moduleConfiguration.componentDisplayName?.endsWith(ARCHIVED_SUFFIX)) {
                            def componentOwner = moduleConfiguration.componentOwner
                            def releaseManagers = moduleConfiguration.releaseManager
                            def securityChampions = moduleConfiguration.securityChampion
                            log.info("Add to employee validation '$componentName'," +
                                    " componentOwner '$componentOwner'," +
                                    " releaseManager '$releaseManagers'," +
                                    " securityChampions '$securityChampions'," +
                                    " displayName: '$moduleConfiguration.componentDisplayName'")

                            ownerComponents.computeIfAbsent(componentOwner, { _ -> new HashSet<>() })
                                    .add(componentName)
                            securityChampions?.split(",")
                                    ?.each { securityChampion ->
                                        if (!StringUtils.isBlank(securityChampion)) {
                                            securityChampionComponents.computeIfAbsent(securityChampion, { _ -> new HashSet<>() })
                                                    .add(componentName)
                                        }
                                    }
                            releaseManagers?.split(",")
                                    ?.each { releaseManager ->
                                        if (!StringUtils.isBlank(releaseManager)) {
                                            releaseManagerComponents.computeIfAbsent(releaseManager, { _ -> new HashSet<>() })
                                                    .add(componentName)
                                        }
                                    }
                        }
                    }
        }

        def errors = findErrors(ownerComponents, "Component Owner") +
                     findErrors(releaseManagerComponents, "Release Manager") +
                     findErrors(securityChampionComponents, "Security Champion")

        if (!errors.isEmpty()) {
            throw new IllegalStateException("Component Owner, Release Manager, Security Champion validation finished with following errors: ${errors.join(". ")}")
        }

        if (jiraHost && oldComponents) {
            def jiraComponents = getJiRAComponents()
            log.info("oldComponents=$oldComponentNames")
            log.info("newComponents=$newComponentNames")
            log.info("jiraComponents=$jiraComponents")
            oldComponentNames.removeAll(newComponentNames)
            def removedComponents = oldComponents
            log.info("removedComponents=$removedComponents")
            def renamedOrDeletedComponents = removedComponents.findAll { jiraComponents.contains(it) }
            if (!renamedOrDeletedComponents.isEmpty()) {
                throw new IllegalStateException("Following component(s) $renamedOrDeletedComponents are deleted but exists in JIRA. Please revert them back or contact to solve this problem.")
            }
        } else {
            log.info("Skipping JIRA validation: jiraHost=${jiraHost}, productionConfigPath=${productionConfigPath}")
        }
    }

    private EmployeeServiceClient getEmployeeServiceClient() {
        return new ClassicEmployeeServiceClient(new EmployeeServiceClientParametersProvider() {
            @Override
            String getApiUrl() {
                return employeeServiceUrl
            }

            @Override
            int getTimeRetryInMillis() {
                return 5000
            }

            @Override
            String getBearerToken() {
                return employeeServiceToken
            }

            @Override
            String getBasicCredentials() {
                return "$employeeServiceUsername:$employeeServicePassword"
            }
        })
    }

    private List<String> findErrors(Map<String, Set<String>> employeeComponentNames, String field) {
        if (employeeServiceEnabled) {
            def employeeServiceClient = getEmployeeServiceClient()
            employeeComponentNames
                    .collect { componentOwner, componentNames ->
                        try {
                            def employee = employeeServiceClient.getEmployee(componentOwner)
                            if (employee.active) {
                                null
                            } else {
                                "$componentOwner is not active, used as '${field}' in ${componentNames.join(", ")}".toString()
                            }
                        } catch (NotFoundException e) {
                            "${e.getMessage()}, used as '$field' in ${componentNames.join(", ")}".toString()
                        }
                    }.findAll { error -> error != null }
        } else {
            log.warn 'Employee validation disabled'
            Collections.emptyList()
        }
    }

    private List<String> getJiRAComponents() {
        def components = []
        try {
            def http = new HTTPBuilder("https://$jiraHost/rest/release-engineering/3/component-management/components")
            http.get(contentType: TEXT) { resp, reader ->
                if (reader != null) {
                    def componentList = reader.text as String
                    components.addAll(componentList.split(","))
                }
            }
        } catch (Exception e) {
            // service may be not available until its not in production
            log.error("Unable to get components from jira", e)
        }
        components
    }

    private Map<String, EscrowModule> getComponentsFromConfig(String configPath, Map<ProductTypes, String> productTypeMap) {
        log.info("Loading: {}", configPath)
        log.info("Product types: {}", productTypeMap.toMapString())
        def loader = new ConfigLoader(
                ComponentRegistryInfo.createFromFileSystem(configPath, mainConfigFileName),
                new VersionNames(serviceBranch, service, minor),
                productTypeMap
        )
        def config = getConfig(loader,
                supportedGroupIds.split(",").collect {it -> it.trim()},
                supportedSystems.split(",").collect {it -> it.trim()},
                serviceBranch,
                service,
                minor
        )
        config.escrowModules
    }

    private static EscrowConfiguration getConfig(ConfigLoader loader,
                                                 List<String> supportedGroupIds,
                                                 List<String> supportedSystems,
                                                 String serviceBranch,
                                                 String service,
                                                 String minor
    ) {
        EscrowConfigurationLoader escrowConfigurationLoader = new EscrowConfigurationLoader(
                loader,
                supportedGroupIds,
                supportedSystems,
                new VersionNames(serviceBranch, service, minor)
        )
        def configuration = escrowConfigurationLoader.loadFullConfiguration(null)
        assert configuration != null
        return configuration
    }
    
    private static String getProperty(String name) {
        return System.getProperty(name)
    }
    
    private static String getRequiredProperty(String name) {
        def value = System.getProperty(name)
        if (value == null) {
            throw new IllegalArgumentException("Required system property '$name' is not set")
        }
        return value
    }
}
