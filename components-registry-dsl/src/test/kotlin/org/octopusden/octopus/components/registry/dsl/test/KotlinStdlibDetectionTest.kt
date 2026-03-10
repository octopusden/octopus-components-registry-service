package org.octopusden.octopus.components.registry.dsl.test

import kotlin.script.experimental.jsr223.KotlinJsr223DefaultScriptEngineFactory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import javax.script.ScriptException

/**
 * Тест для воспроизведения проблемы с kotlin.java.stdlib.jar
 * 
 * Проблема: в Spring Boot fat jar, Kotlin JSR223 script engine не может
 * автоматически найти kotlin-stdlib и требует явной установки свойства
 * kotlin.java.stdlib.jar
 * 
 * Этот тест пытается инициализировать Kotlin script engine БЕЗ установки
 * kotlin.java.stdlib.jar property, чтобы воспроизвести ошибку из OKD QA.
 */
class KotlinStdlibDetectionTest {

    @Test
    fun `test Kotlin script engine initialization without stdlib property`() {
        println("=== Testing Kotlin Script Engine Initialization ===")
        println()
        
        // Выводим текущие system properties
        println("Current system properties:")
        println("  kotlin.script.classpath = ${System.getProperty("kotlin.script.classpath")}")
        println("  kotlin.java.stdlib.jar = ${System.getProperty("kotlin.java.stdlib.jar")}")
        println("  java.class.path length = ${System.getProperty("java.class.path").length} chars")
        println()
        
        // Сохраняем текущее значение kotlin.java.stdlib.jar (если есть)
        val originalStdlibProperty = System.getProperty("kotlin.java.stdlib.jar")
        
        try {
            // КРИТИЧНО: Убираем kotlin.java.stdlib.jar property чтобы воспроизвести проблему
            System.clearProperty("kotlin.java.stdlib.jar")
            println("Cleared kotlin.java.stdlib.jar property to simulate OKD environment")
            println()
            
            println("Creating KotlinJsr223DefaultScriptEngineFactory...")
            val factory = KotlinJsr223DefaultScriptEngineFactory()
            
            println("Getting script engine...")
            val engine = factory.scriptEngine
            
            println("Script engine created successfully!")
            println("Engine: ${engine.javaClass.name}")
            println()
            
            // Попробуем выполнить простой скрипт
            println("Evaluating simple Kotlin script...")
            val result = engine.eval("println(\"Hello from Kotlin!\"); 42")
            println("Result: $result")
            println()
            
            println("✅ SUCCESS: Kotlin script engine works without kotlin.java.stdlib.jar property!")
            println("   This means the issue is NOT reproduced in unit test environment.")
            println("   The issue might only occur in fat jar / OKD environment.")
            
        } catch (e: ScriptException) {
            println()
            println("❌ FAILED with ScriptException: ${e.message}")
            
            // Проверяем, это ли ошибка про kotlin stdlib
            if (e.message?.contains("kotlin stdlib", ignoreCase = true) == true) {
                println()
                println("⚠️  REPRODUCED: This is the kotlin.java.stdlib.jar issue!")
                println("   The script engine cannot find kotlin-stdlib in the classpath.")
                println()
                e.printStackTrace()
                
                // Это ожидаемая ошибка для воспроизведения проблемы
                // НЕ fail test, потому что мы ХОТИМ воспроизвести эту ошибку
                println()
                println("✅ Issue successfully reproduced in unit test!")
                return
            }
            
            // Если это другая ошибка - fail test
            throw e
            
        } catch (e: Exception) {
            println()
            println("❌ FAILED with ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
            throw e
            
        } finally {
            // Восстанавливаем original property
            if (originalStdlibProperty != null) {
                System.setProperty("kotlin.java.stdlib.jar", originalStdlibProperty)
            }
        }
    }
    
    @Test
    fun `test Kotlin script engine with explicit stdlib property`() {
        println("=== Testing Kotlin Script Engine with Explicit stdlib Property ===")
        println()
        
        // Пытаемся найти kotlin-stdlib в classpath
        val classpath = System.getProperty("java.class.path")
        val stdlibPath = classpath.split(System.getProperty("path.separator"))
            .find { it.contains("kotlin-stdlib") && it.endsWith(".jar") }
        
        println("Detected kotlin-stdlib in classpath: $stdlibPath")
        println()
        
        if (stdlibPath != null) {
            // Устанавливаем kotlin.java.stdlib.jar property
            System.setProperty("kotlin.java.stdlib.jar", stdlibPath)
            println("Set kotlin.java.stdlib.jar = $stdlibPath")
            println()
        }
        
        try {
            println("Creating KotlinJsr223DefaultScriptEngineFactory...")
            val factory = KotlinJsr223DefaultScriptEngineFactory()
            
            println("Getting script engine...")
            val engine = factory.scriptEngine
            
            println("✅ SUCCESS: Script engine created!")
            println("Engine: ${engine.javaClass.name}")
            
            // Этот тест должен всегда проходить
            assertNotNull(engine)
            
        } catch (e: Exception) {
            println("❌ FAILED: ${e.message}")
            e.printStackTrace()
            fail("Script engine should work with explicit stdlib property")
        }
    }
}
