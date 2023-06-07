package org.octopusden.octopus.escrow.resolvers

import org.octopusden.octopus.escrow.configuration.loader.IScriptClassLoader
import org.apache.commons.lang3.Validate
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

abstract class ComposedConfigScript extends Script {
    Logger log = LogManager.getLogger(ComposedConfigScript.class)

    private IScriptClassLoader scriptClassLoader

    void setScriptClassLoader(IScriptClassLoader scriptClassLoader) {
        this.scriptClassLoader = scriptClassLoader
    }

    def includeScript(scriptClass) {
        def scriptInstance = scriptClass.newInstance()

        scriptInstance.metaClass = this.metaClass

        scriptInstance.binding = new ConfigBinding(this.getBinding().callable)
        scriptInstance.binding.getVariables().putAll(this.getBinding().getVariables());

        scriptInstance.&run.call()
    }

    protected void include(String configName) {
        log.debug "include $configName"
        Validate.notNull(scriptClassLoader)
        Validate.notNull(configName, "script name can't be null")
        def script = scriptClassLoader.loadScript(configName);
        includeScript(script)
    }
}
