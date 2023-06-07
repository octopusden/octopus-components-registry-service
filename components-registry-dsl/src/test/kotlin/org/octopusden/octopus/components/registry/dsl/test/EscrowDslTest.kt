package org.octopusden.octopus.components.registry.dsl.test

import org.octopusden.octopus.components.registry.dsl.component
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.lang.IllegalStateException
import java.util.stream.Stream

@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock(value = "DSL")
class EscrowDslTest {

    @ParameterizedTest
    @MethodSource("multipliers")
    fun testDiskspaceRequirementMultiplier(diskspaceRequirement: String, bytes: Long) {
        var components = registryDsl {
            component("FC Barcelona") {
                escrow {
                    diskspace = diskspaceRequirement
                }
            }
        }
        assertEquals(bytes, components.getValue("FC Barcelona").escrow.diskSpaceRequirement.orElseThrow { IllegalStateException() })
    }

    @Test
    fun testDiskspaceRequirement() {
        var components = registryDsl {
            component("manager") {
                escrow {
                    diskspace = 32
                }
            }
            component("component_commons") {
            }
            component("octopusweb") {
                escrow {
                }
            }
        }
        assertEquals(32, components.getValue("manager").escrow.diskSpaceRequirement.orElseThrow { IllegalStateException() })
        assertFalse(components.getValue("component_commons").escrow.diskSpaceRequirement.isPresent)
        assertFalse(components.getValue("octopusweb").escrow.diskSpaceRequirement.isPresent)
    }

    @Test
    fun testVersionLessEscrowDsl() {
        var components = registryDsl {
            component("manager") {
                escrow {
                    gradle {
                        includeTestConfigurations = true
                    }
                }
            }
            component("component_commons") {
            }
            component("octopusweb") {
                escrow {
                    gradle {
                    }
                }
            }
        }
        assertTrue(components.getValue("manager").escrow.gradle.includeTestConfigurations)
        assertFalse(components.getValue("component_commons").escrow.gradle.includeTestConfigurations)
        assertFalse(components.getValue("octopusweb").escrow.gradle.includeTestConfigurations)

        components = registryDsl {
            component("manager") {
            }
            component("component_commons") {
                escrow {
                    gradle {
                        includeTestConfigurations = true
                    }
                }
            }
            component("octopusweb") {
                escrow {
                    gradle {
                    }
                }
            }
        }
        assertFalse(components.getValue("manager").escrow.gradle.includeTestConfigurations)
        assertTrue(components.getValue("component_commons").escrow.gradle.includeTestConfigurations)
        assertFalse(components.getValue("octopusweb").escrow.gradle.includeTestConfigurations)

        components = registryDsl {
            component("manager") {
            }
            component("component_commons") {
                escrow {
                    gradle {
                    }
                }
            }
            component("octopusweb") {
                escrow {
                    gradle {
                        includeTestConfigurations = true
                    }
                }
            }
        }
        assertFalse(components.getValue("manager").escrow.gradle.includeTestConfigurations)
        assertFalse(components.getValue("component_commons").escrow.gradle.includeTestConfigurations)
        assertTrue(components.getValue("octopusweb").escrow.gradle.includeTestConfigurations)
    }

    @Test
    fun testEscrowInherit() {
        val components = registryDsl {
            component("component_commons") {
                escrow {
                    gradle {
                        includeTestConfigurations = true
                    }
                }
                version("[0, 2]") {
                }
                version("(2,)") {
                    escrow {
                        gradle {
                            includeTestConfigurations = false
                        }
                    }
                }
                components {
                    component("octopusweb") {
                    }
                }
            }
        }
        assertTrue(components.getValue("component_commons").escrow.gradle.includeTestConfigurations)
        assertTrue(components.getValue("component_commons").versions.getValue("[0, 2]").escrow.gradle.includeTestConfigurations)
        assertFalse(components.getValue("component_commons").versions.getValue("(2,)").escrow.gradle.includeTestConfigurations)
        assertFalse(components.getValue("component_commons").subComponents.getValue("octopusweb").escrow.gradle.includeTestConfigurations)
    }

    @Test
    fun testEscrowPropertiesPropagation() {
        val components = registryDsl {

            component("component_commons") {
                escrow {
                    gradle {
                        includeConfigurations = listOf("1", "2")
                    }
                }
                version("(0,1)") {
                    escrow {
                        gradle {
                            includeConfiguration("3")
                        }
                    }
                }
                version("(1,2)") {
                    escrow {
                        gradle {
                            includeConfigurations = listOf("4")
                        }
                    }
                }
                version("(2,3)") {
                    escrow {
                        gradle {
                            includeConfigurations = listOf("5")
                            includeConfiguration("6")
                        }
                    }
                }
            }
        }
        assertThat(components.getValue("component_commons").escrow.gradle.includeConfigurations).containsExactly("1", "2")
        assertThat(components.getValue("component_commons").versions.getValue("(0,1)").escrow.gradle.includeConfigurations).containsExactly("1", "2", "3")
        assertThat(components.getValue("component_commons").versions.getValue("(1,2)").escrow.gradle.includeConfigurations).containsExactly("4")
        assertThat(components.getValue("component_commons").versions.getValue("(2,3)").escrow.gradle.includeConfigurations).containsExactly("5", "6")
    }

    @Test
    fun testEscrowProvidedDependencies() {
        val components = registryDsl {
            component("component_commons") {
                escrow {
                    providedDependencies = listOf("a:b:v1")
                }
                version("(0,1)") {
                    escrow {
                        providedDependencies += "a:b:v2"
                    }
                }
                version("(1,2)") {
                }
                version("(2,3)") {
                    escrow {
                        providedDependencies = listOf("a:b:v3")
                    }
                }
                version("(3,4)") {
                    escrow {
                        providedDependencies += listOf("a:b:v4", "a:b:v5")
                    }
                }
                version("(4,5)") {
                    escrow {
                        providedDependencies = emptyList()
                    }
                }
            }
        }
        assertThat(components.getValue("component_commons").escrow.providedDependencies).containsExactly("a:b:v1")
        assertThat(components.getValue("component_commons").versions.getValue("(0,1)").escrow.providedDependencies).containsExactly("a:b:v1", "a:b:v2")
        assertThat(components.getValue("component_commons").versions.getValue("(1,2)").escrow.providedDependencies).containsExactly("a:b:v1")
        assertThat(components.getValue("component_commons").versions.getValue("(2,3)").escrow.providedDependencies).containsExactly("a:b:v3")
        assertThat(components.getValue("component_commons").versions.getValue("(3,4)").escrow.providedDependencies).containsExactly("a:b:v1", "a:b:v4", "a:b:v5")
        assertThat(components.getValue("component_commons").versions.getValue("(4,5)").escrow.providedDependencies).isEmpty()
    }

    @Test
    fun testAdditionalSources() {
        val components = registryDsl {
            component("manager") {
                escrow {
                    additionalSources = listOf("module/build/node_modules")
                }
            }
        }
        assertThat(components.getValue("manager").escrow.additionalSources).containsExactly("module/build/node_modules")
    }

    @Test
    @DisplayName("Test escrow reusable attribute")
    fun testEscrowReusable() {
        val components = registryDsl {
            component("dbsm-api") { }
            component("dbsm-core") {
                escrow {
                    reusable = false
                }
            }
            component("dbsm-service") {
                escrow {
                    reusable = true
                }
            }
        }
        assertThat(components.getValue("dbsm-api").escrow.isReusable).isTrue()
        assertThat(components.getValue("dbsm-core").escrow.isReusable).isFalse()
        assertThat(components.getValue("dbsm-service").escrow.isReusable).isTrue()
    }

    companion object {
        @JvmStatic
        fun multipliers(): Stream<Arguments> {
            return Stream.of(
                Arguments.of("24G", 24 * 1024 * 1024 * 1024L),
                Arguments.of("24GB", 24 * 1024 * 1024 * 1024L),
                Arguments.of("2M", 2 * 1024 * 1024L),
                Arguments.of("2MB", 2 * 1024 * 1024L),
                Arguments.of("16K", 16 * 1024L),
                Arguments.of("16KB", 16 * 1024L),
                Arguments.of("512", 512L)
            )
        }
    }
}