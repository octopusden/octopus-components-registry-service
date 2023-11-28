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
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.octopusden.releng.versions.VersionNames

import static groovyx.net.http.ContentType.TEXT

class ComponentRegistryValidationTask extends DefaultTask {

    static final String ARCHIVED_SUFFIX = "(archived)"
    @Input
    String basePath
    @Input
    String mainConfigFileName
    @Input
    String jiraHost
    private boolean employeeServiceEnabled
    @Input
    String employeeServiceUrl
    @Input
    String employeeServiceUsername
    @Input
    String employeeServicePassword
    @Input
    String employeeServiceToken
    @Input
    String productionConfigPath
    @Input
    String supportedGroupIds
    @Input
    String supportedSystems
    @Input
    String systemMandatory
    @Input
    String serviceBranch
    @Input
    String service
    @Input
    String minor
    @Input
    String productTypeC
    @Input
    String productTypeK
    @Input
    String productTypeD
    @Input
    String productTypeDDB


    @Input
    boolean isEmployeeServiceEnabled() {
        return employeeServiceEnabled
    }

    void setEmployeeServiceEnabled(boolean employeeServiceEnabled) {
        this.employeeServiceEnabled = employeeServiceEnabled
    }

    @TaskAction
    def runEscrow() {
        getLogger().info "user.dir = ${System.getProperty('user.dir')} \n" +
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

        def oldComponents = getComponentsFromConfig(productionConfigPath, productTypeMap)
        def newComponents = getComponentsFromConfig(basePath, productTypeMap)

        def oldComponentNames = oldComponents.keySet()
        def newComponentNames = newComponents.keySet()

        final Map<String, Set<String>> ownerComponents = new HashMap<>()
        final Map<String, Set<String>> releaseManagerComponents = new HashMap<>()
        final Map<String, Set<String>> securityChampionComponents = new HashMap<>()

        newComponents.each { componentName, component ->
            component.moduleConfigurations
                    .each { moduleConfiguration ->
                        if (!moduleConfiguration.componentDisplayName?.endsWith(ARCHIVED_SUFFIX)) {
                            def componentOwner = moduleConfiguration.componentOwner
                            def releaseManager = moduleConfiguration.releaseManager
                            def securityChampions = moduleConfiguration.securityChampion
                            getLogger().info("Add to employee validation '$componentName'," +
                                    " componentOwner '$componentOwner'," +
                                    " releaseManager '$releaseManager'," +
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
                            if (!StringUtils.isBlank(releaseManager)) {
                                releaseManagerComponents.computeIfAbsent(releaseManager, { _ -> new HashSet<>() })
                                        .add(componentName)
                            }
                        }
                    }
        }

        def errors = findErrors(ownerComponents, "Component Owner") +
                findErrors(releaseManagerComponents, "Release Manager") +
                findErrors(securityChampionComponents, "Security Champion")

        if (!errors.isEmpty()) {
            throw new GradleException("Component Owner, Release Manager, Security Champion validation finished with following errors: ${errors.join(". ")}")
        }

        def jiraComponents = getJiRAComponents()
        logger.info("oldComponents=$oldComponentNames")
        logger.info("newComponents=$newComponentNames")
        logger.info("jiraComponents=$jiraComponents")
        oldComponentNames.removeAll(newComponentNames)
        def removedComponents = oldComponents
        logger.info("removedComponents=$removedComponents")
        def renamedOrDeletedComponents = removedComponents.findAll { jiraComponents.contains(it) }
        if (!renamedOrDeletedComponents.isEmpty()) {
            throw new GradleException("Following component(s) $renamedOrDeletedComponents are deleted but exists in JIRA. Please revert them back or contact to solve this problem.")
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
            getLogger().warn 'Employee validation disabled'
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
            logger.error("Unable to get components from jira", e)
        }
        components
    }

    private Map<String, EscrowModule> getComponentsFromConfig(String configPath, Map<ProductTypes, String> productTypeMap) {
        getLogger().info("Loading: {}", configPath)
        getLogger().info("Product types: {}", productTypeMap.toMapString())
        def loader = new ConfigLoader(
                ComponentRegistryInfo.createFromFileSystem(configPath, mainConfigFileName),
                new VersionNames(serviceBranch, service, minor),
                productTypeMap
        )
        def config = getConfig(loader,
                supportedGroupIds.split(",").collect {it -> it.trim()},
                systemMandatory,
                supportedSystems.split(",").collect {it -> it.trim()},
                serviceBranch,
                service,
                minor
        )
        config.escrowModules
    }

    private static EscrowConfiguration getConfig(ConfigLoader loader,
                                                 List<String> supportedGroupIds,
                                                 boolean systemMandatory,
                                                 List<String> supportedSystems,
                                                 String serviceBranch,
                                                 String service,
                                                 String minor
    ) {
        EscrowConfigurationLoader escrowConfigurationLoader = new EscrowConfigurationLoader(
                loader,
                supportedGroupIds,
                systemMandatory,
                supportedSystems,
                new VersionNames(serviceBranch, service, minor)
        )
        def configuration = escrowConfigurationLoader.loadFullConfiguration(null)
        assert configuration != null
        return configuration
    }
}
