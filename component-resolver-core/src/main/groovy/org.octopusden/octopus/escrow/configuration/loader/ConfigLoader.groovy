package org.octopusden.octopus.escrow.configuration.loader

import groovy.transform.TupleConstructor
import groovy.transform.TypeChecked
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.octopusden.octopus.components.registry.api.Component
import org.octopusden.octopus.components.registry.api.enums.ProductTypes
import org.octopusden.octopus.components.registry.dsl.script.ComponentsRegistryScriptRunner
import org.octopusden.octopus.escrow.BuildSystem
import org.octopusden.octopus.escrow.RepositoryType
import org.octopusden.octopus.escrow.configuration.validation.GroovySlurperConfigValidator
import org.octopusden.octopus.escrow.resolvers.ComposedConfigScript
import org.octopusden.releng.versions.VersionNames
import org.yaml.snakeyaml.Yaml

import java.nio.file.Path
import java.nio.file.Paths

@TupleConstructor
@TypeChecked
class ConfigLoader implements IConfigLoader {

    private static final Logger LOG = LogManager.getLogger(ConfigLoader.class)

    private final VersionNames versionNames
    private final ComponentRegistryInfo componentRegistryInfo
    private final Map<ProductTypes, String> products

    private final Path validationConfigPath

    ConfigLoader(ComponentRegistryInfo componentRegistryInfo, VersionNames versionNames, Map<ProductTypes, String> products) {
        this.componentRegistryInfo = componentRegistryInfo
        this.versionNames = versionNames
        this.products = products
        this.validationConfigPath = Paths.get(componentRegistryInfo.basePath, "validation-config.yaml")
    }

    @Override
    ConfigObject loadModuleConfig() {
        return loadConfigWithConfigSlurper([:], true)
    }


    @Override
    ConfigObject loadModuleConfig(Map<String, String> params) {
        return loadConfigWithConfigSlurper(preProcessParams(params), true)
    }

    private static Map<String, String> preProcessParams(Map<String, String> params) {
        def preProcessedParams = [:] as Map<String, String>
        if (params == null) {
            return preProcessedParams
        }
        params.each { String key, String value ->
            String keyReplaced = key.replaceAll("\\.", "_")
            preProcessedParams.put(keyReplaced, value)
        }
        return preProcessedParams
    }

    private ConfigObject loadConfigWithConfigSlurper(Map<String, String> binding, boolean validateConfig) {
        Objects.requireNonNull(componentRegistryInfo)
        LOG.info("Loading config with binding: $binding")
        GroovyClassLoader classLoader = new GroovyClassLoader()
        classLoader.loadClass(BuildSystem.class.getCanonicalName())
        classLoader.loadClass(RepositoryType.class.getCanonicalName())

        def scriptClassLoaderFactory = new ScriptClassLoaderFactory(classLoader)
        def scriptClassLoader = scriptClassLoaderFactory.createScriptClassLoader(componentRegistryInfo)

        final Script script
        final Class clazz
        clazz = scriptClassLoader.loadScript(componentRegistryInfo.mainConfigName)
        script = clazz.newInstance() as Script

        if (script instanceof ComposedConfigScript) { //todo
            ((ComposedConfigScript) script).setScriptClassLoader(scriptClassLoader)
        }

        ConfigSlurper slurper = new ConfigSlurper()
        slurper.setBinding(binding)
        ConfigObject config = slurper.parse(script)
        GroovySystem.getMetaClassRegistry().removeMetaClass(script.getClass())
        classLoader.clearCache()
        if (validateConfig) {
            ConfigLoader.validateConfig(config, versionNames)
        }
        return config
    }

    @Override
    ConfigObject loadModuleConfigWithoutValidationForUnknownAttributes(Map<String, String> params) {
        LOG.debug("Loading w/o binding and validation for unknown attributes. Params: $params ")
        return loadConfigWithConfigSlurper(preProcessParams(params), false)
    }

    @Override
    Collection<Component> loadDslDefinedComponents() {
        return ComponentsRegistryScriptRunner.INSTANCE.loadDSL(Paths.get(componentRegistryInfo.basePath), products)
    }

    /**
     * Load list of components excluded from validation
     * @return
     */
    @Override
    List<String> loadDistributionValidationExcludedComponents() {
        return loadValuesFromValidationConfig(['distribution', 'ee', 'exclude'])
    }

    /**
     * Load list of available labels from validation
     * @return
     */
    @Override
    Set<String> loadAvailableLabels() {
        return loadValuesFromValidationConfig(['labels']).toSet()
    }

    def static validateConfig(ConfigObject configObject, VersionNames versionNames) {
        def validator = new GroovySlurperConfigValidator(versionNames)
        validator.validateConfig(configObject)
    }

    private List<String> loadValuesFromValidationConfig(List<String> fieldsChain) {
        List<String> resultValues = []
        LOG.info("Loading values from YAML file: $validationConfigPath")

        def file = validationConfigPath.toFile()
        if (!file.exists()) {
            LOG.warn("YAML file $validationConfigPath does not exist")
            return resultValues
        }

        LOG.info("File $validationConfigPath exists. Loading values from YAML")
        try {
            def yamlContent = new Yaml().loadAs(file.text, Map)
            def values = getValuesByFieldChain(yamlContent, fieldsChain)

            if (values) {
                resultValues.addAll(values)
                LOG.info("Found ${resultValues.size()} values in YAML")
            } else {
                LOG.info("No '${fieldsChain.join(".")}' section found in YAML file")
            }
        } catch (Exception e) {
            LOG.error("Error parsing YAML file: ${e.message}", e)
            throw e
        }
        return resultValues as List<String>
    }

    private static List<String> getValuesByFieldChain(Map yamlContent, List<String> fieldsChain) {
        return fieldsChain.inject(yamlContent) { accumulator, element ->
            accumulator instanceof Map ? accumulator[element] : null
        } as List<String>
    }
}
