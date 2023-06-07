package org.octopusden.octopus.escrow.configuration.loader

import groovy.transform.TypeChecked
import org.apache.commons.lang3.Validate
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

@TypeChecked
class ClassPathScriptClassLoader implements IScriptClassLoader {

    Logger log = LogManager.getLogger(ClassPathScriptClassLoader.class)

    private final GroovyClassLoader groovyClassLoader;

    ClassPathScriptClassLoader(GroovyClassLoader groovyClassLoader) {
        Validate.notNull(groovyClassLoader, "groovyClassLoader can't be null")
        this.groovyClassLoader = groovyClassLoader
    }

    @Override
    Class loadScript(String scriptName) {
        Validate.notEmpty(scriptName, "scriptName can't be empty")
        log.info("Loading $scriptName from classpath")
        def configScript = groovyClassLoader.parseClass(groovyClassLoader.getResource(scriptName).text);
        return configScript
    }
}
