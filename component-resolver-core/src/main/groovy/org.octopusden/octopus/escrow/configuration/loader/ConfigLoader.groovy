package org.octopusden.octopus.escrow.configuration.loader

import org.octopusden.octopus.components.registry.api.Component
import org.octopusden.octopus.components.registry.dsl.script.ComponentsRegistryScriptRunner
import org.octopusden.octopus.escrow.BuildSystem
import org.octopusden.octopus.escrow.RepositoryType
import org.octopusden.octopus.escrow.configuration.validation.GroovySlurperConfigValidator
import org.octopusden.octopus.escrow.resolvers.ComposedConfigScript
import groovy.transform.TupleConstructor
import groovy.transform.TypeChecked
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

import java.nio.file.Paths

@TupleConstructor
@TypeChecked
class ConfigLoader implements IConfigLoader {

    private static final Logger LOG = LogManager.getLogger(ConfigLoader.class)

    private ComponentRegistryInfo componentRegistryInfo

    ConfigLoader(ComponentRegistryInfo componentRegistryInfo) {
        this.componentRegistryInfo = componentRegistryInfo
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
            ConfigLoader.validateConfig(config)
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
        return ComponentsRegistryScriptRunner.INSTANCE.loadDSL(Paths.get(componentRegistryInfo.basePath))
    }

    def static validateConfig(ConfigObject configObject) {
        def validator = new GroovySlurperConfigValidator()
        validator.validateConfig(configObject)
    }
}
