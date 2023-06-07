package org.octopusden.octopus.escrow.configuration.loader

import org.apache.commons.lang3.Validate

class ScriptClassLoaderFactory {

    private final GroovyClassLoader groovyClassLoader

    ScriptClassLoaderFactory(GroovyClassLoader groovyClassLoader) {
        this.groovyClassLoader = groovyClassLoader
    }

    public IScriptClassLoader createScriptClassLoader(ComponentRegistryInfo componentRegistryInfo) {
        Validate.notNull(componentRegistryInfo, "componentRegistryInfo can't be null")
        return componentRegistryInfo.getFromClassPath() ?
                new ClassPathScriptClassLoader(groovyClassLoader) :
                new FileSystemScriptClassLoader(componentRegistryInfo.basePath, groovyClassLoader);

    }

}
