package org.octopusden.octopus.components.registry.org.octopusden.octopus.components.registry.automation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.octopusden.octopus.components.registry.automation.ComponentsRegistryCommand.Companion.URL_OPTION
import org.octopusden.octopus.components.registry.automation.ComponentsRegistryDownloadCopyright.Companion.COMMAND
import org.octopusden.octopus.components.registry.automation.ComponentsRegistryDownloadCopyright.Companion.COMPONENT_NAME
import org.octopusden.octopus.components.registry.automation.ComponentsRegistryDownloadCopyright.Companion.DEFAULT_COPYRIGHT_FILE_NAME
import org.octopusden.octopus.components.registry.automation.ComponentsRegistryDownloadCopyright.Companion.TARGET_PATH
import java.io.File
import java.nio.file.Paths
import java.util.stream.Stream

class ApplicationTest {

    private val jar = System.getProperty("jar")
        ?: throw IllegalStateException("System property 'jar' must be provided")

    /**
     * Test successful downloading components and checking existence files
     */
    @ParameterizedTest
    @MethodSource("componentWithCopyrightCommands")
    fun successfulDownloadingTest(
        name: String,
        actualCopyrightContent: String,
        expectedExitCode: Int,
        commands: Array<String>,
    ) {
        assertEquals(expectedExitCode, execute(name, *commands))
        assertTrue(copyrightFile.exists())
        val expectedContent = getDownloadedFileContent()
        assertEquals(expectedContent, actualCopyrightContent)
    }

    /**
     * Test unsuccessful downloading components cause of copyright not specified
     */
    @ParameterizedTest
    @MethodSource("componentWithoutCopyrightCommands")
    fun unsuccessfulDownloadingTest(
        name: String,
        expectedExitCode: Int,
        commands: Array<String>,
    ) {
        assertEquals(expectedExitCode, execute(name, *commands))
    }

    /**
     * Test unsuccessful downloading components cause of component not exists
     */
    @ParameterizedTest
    @MethodSource("nonExistentComponentCommand")
    fun downloadingNonExistentComponent(
        name: String,
        expectedExitCode: Int,
        commands: Array<String>,
    ) {
        assertEquals(expectedExitCode, execute(name, *commands))
    }

    /**
     * Test checking different command arguments
     */
    @ParameterizedTest
    @MethodSource("differentArgumentsCommands")
    fun checkingDifferentArguments(
        name: String,
        expectedExitCode: Int,
        commands: Array<String>,
    ) {
        assertEquals(expectedExitCode, execute(name, *commands))
    }

    private fun getDownloadedFileContent(): String = copyrightFile.readText(Charsets.UTF_8)

    private fun execute(name: String, vararg commands: String) {
        try {
            ProcessBuilder("java", "-jar", jar, *commands)
                .redirectErrorStream(true)
                .redirectOutput(
                    File("")
                        .resolve("build")
                        .resolve("logs")
                        .resolve("$name.log")
                        .also { it.parentFile.mkdirs() }
                )
                .start()
                .waitFor()
        } catch (e: Exception) {
            println("Failed to execute $name: ${e.message}")
        }
    }

    companion object {

        private const val CORRECT_EXIT_CODE = 0
        private const val INCORRECT_COMMAND_EXIT_CODE = 1
        private const val NOT_EXIST_EXIT_CODE = 404
        private const val TEST_TARGET_PATH = "downloaded_copyrights"
        private const val HELP_OPTION = "-h"

        private val componentsRegistryServiceHost = System.getProperty("test.components-registry-service-host")
            ?: throw Exception("System property 'test.components-registry-service-host' must be defined")

        private val testComponentsRegistryServiceHost = "http://$componentsRegistryServiceHost"
        private val componentsRegistryCommandOptions = "${URL_OPTION}=$testComponentsRegistryServiceHost"

        private val copyrightFile by lazy {
            File(
                Paths.get(
                    TEST_TARGET_PATH,
                    DEFAULT_COPYRIGHT_FILE_NAME
                ).toUri()
            )
        }

        @JvmStatic
        private fun componentWithCopyrightCommands(): Stream<Arguments> = Stream.of(
            Arguments.of(
                "Test downloading 'ee-component'",
                "EE component copyright content",
                CORRECT_EXIT_CODE,
                arrayOf(
                    componentsRegistryCommandOptions,
                    *createDownloadCopyrightCommandByComponentName("ee-component")
                )
            ),
            Arguments.of(
                "Test downloading 'ee-client-specific-component'",
                "EE client specific component copyright content",
                CORRECT_EXIT_CODE,
                arrayOf(
                    componentsRegistryCommandOptions,
                    *createDownloadCopyrightCommandByComponentName("ee-client-specific-component")
                )
            ),
            Arguments.of(
                "Test downloading 'dependency1'",
                "Dependency 1 copyright content",
                CORRECT_EXIT_CODE,
                arrayOf(
                    componentsRegistryCommandOptions,
                    *createDownloadCopyrightCommandByComponentName("dependency1")
                )
            ),
            Arguments.of(
                "Test downloading 'dependency2'",
                "Dependency 2 copyright content",
                CORRECT_EXIT_CODE,
                arrayOf(
                    componentsRegistryCommandOptions,
                    *createDownloadCopyrightCommandByComponentName("dependency2")
                )
            ),
        )

        @JvmStatic
        private fun componentWithoutCopyrightCommands(): Stream<Arguments> = Stream.of(
            Arguments.of(
                "Test downloading 'ie-component'",
                NOT_EXIST_EXIT_CODE,
                arrayOf(
                    componentsRegistryCommandOptions,
                    *createDownloadCopyrightCommandByComponentName("ie-component")
                )
            ),
            Arguments.of(
                "Test downloading 'ei-component'",
                NOT_EXIST_EXIT_CODE,
                arrayOf(
                    componentsRegistryCommandOptions,
                    *createDownloadCopyrightCommandByComponentName("ei-component")
                )
            ),
            Arguments.of(
                "Test downloading 'ii-component'",
                NOT_EXIST_EXIT_CODE,
                arrayOf(
                    componentsRegistryCommandOptions,
                    *createDownloadCopyrightCommandByComponentName("ii-component")
                )
            ),
        )

        @JvmStatic
        private fun nonExistentComponentCommand(): Stream<Arguments> = Stream.of(
            Arguments.of(
                "Test downloading 'non-existent-component'",
                NOT_EXIST_EXIT_CODE,
                arrayOf(
                    componentsRegistryCommandOptions,
                    *createDownloadCopyrightCommandByComponentName("non-existent-component")
                )
            )
        )

        @JvmStatic
        private fun differentArgumentsCommands(): Stream<Arguments> = Stream.of(
            Arguments.of(
                "Test help",
                CORRECT_EXIT_CODE,
                arrayOf(HELP_OPTION)
            ),
            Arguments.of(
                "Test empty command",
                INCORRECT_COMMAND_EXIT_CODE,
                arrayOf<String>()
            ),
            Arguments.of(
                "Test '--component-name' not specified",
                INCORRECT_COMMAND_EXIT_CODE,
                arrayOf(
                    componentsRegistryCommandOptions,
                    COMMAND,
                    "${TARGET_PATH}=$TEST_TARGET_PATH",
                )
            ),
            Arguments.of(
                "Test '--target-path' not specified",
                INCORRECT_COMMAND_EXIT_CODE,
                arrayOf(
                    componentsRegistryCommandOptions,
                    COMMAND,
                    "${COMPONENT_NAME}=ee-component",
                )
            ),
        )

        private fun createDownloadCopyrightCommandByComponentName(componentName: String) =
            arrayOf(
                COMMAND,
                "${COMPONENT_NAME}=$componentName",
                "${TARGET_PATH}=$TEST_TARGET_PATH",
            )
    }
}
