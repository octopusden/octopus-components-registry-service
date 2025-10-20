package org.octopusden.octopus.components.registry.dsl.script

import javax.script.ScriptEngine
import javax.script.ScriptEngineFactory
import org.jetbrains.kotlin.cli.common.environment.setIdeaIoUseFallback
import org.jetbrains.kotlin.mainKts.jsr223.KotlinJsr223MainKtsScriptEngineFactory

class LocalKotlinEngineFactory : ScriptEngineFactory {
    override fun getEngineName(): String? = "kotlin-local"

    override fun getEngineVersion(): String? = "1.9.22"

    override fun getExtensions(): List<String?>? = listOf("kts")

    override fun getMimeTypes(): List<String?>? = listOf("text/x-kotlin")

    override fun getNames(): List<String?>? = listOf(
        "kotlin",
        "kotlin-local"
    )

    override fun getLanguageName(): String? = "kotlin"

    override fun getLanguageVersion(): String? = "1.9.22"

    override fun getParameter(key: String?): Any? = null

    override fun getMethodCallSyntax(
        obj: String?,
        m: String?,
        vararg args: String?
    ): String? = "$obj.$m(${args.joinToString()})"

    override fun getOutputStatement(toDisplay: String?): String? = "println($toDisplay)"

    override fun getProgram(vararg statements: String?): String? = statements.joinToString("\n")

    override fun getScriptEngine(): ScriptEngine {
        setIdeaIoUseFallback()
        println("[LocalKotlinEngineFactory] Initializing KotlinJsr223MainKtsScriptEngineFactory")
        return KotlinJsr223MainKtsScriptEngineFactory().getScriptEngine()
    }
}
