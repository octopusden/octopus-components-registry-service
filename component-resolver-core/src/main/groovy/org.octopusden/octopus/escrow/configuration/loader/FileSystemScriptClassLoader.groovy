package org.octopusden.octopus.escrow.configuration.loader

import groovy.transform.TypeChecked
import org.apache.commons.lang3.Validate
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

@TypeChecked
class FileSystemScriptClassLoader implements IScriptClassLoader {
    Logger log = LogManager.getLogger(FileSystemScriptClassLoader.class)

    private final String basePath;

    private final GroovyClassLoader groovyClassLoader;

    FileSystemScriptClassLoader(String basePath, GroovyClassLoader groovyClassLoader) {
        Validate.notNull(basePath, "basePath can't be null")
        Validate.notNull(groovyClassLoader, "groovyClassLoader can't be null")
        this.basePath = basePath
        this.groovyClassLoader = groovyClassLoader
    }

    @Override
    Class loadScript(String scriptName) {
        def defaultsFile = new File(this.basePath + File.separator + scriptName)
        log.info("Loading ${defaultsFile.getAbsolutePath()}")
        def configScript = groovyClassLoader.parseClass(defaultsFile);
        return configScript
    }
}
