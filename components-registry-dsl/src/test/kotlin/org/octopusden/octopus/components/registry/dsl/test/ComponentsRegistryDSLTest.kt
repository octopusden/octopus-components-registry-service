package org.octopusden.octopus.components.registry.dsl.test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.octopusden.octopus.components.registry.api.build.tools.databases.OracleDatabaseTool
import org.octopusden.octopus.components.registry.api.build.tools.oracle.OdbcTool
import org.octopusden.octopus.components.registry.api.enums.BuildToolTypes
import org.octopusden.octopus.components.registry.api.enums.ProductTypes
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.octopusden.octopus.components.registry.dsl.PT_D
import org.octopusden.octopus.components.registry.dsl.PT_D_DB
import org.octopusden.octopus.components.registry.dsl.PT_K
import org.octopusden.octopus.components.registry.dsl.component

/**
 * Parallel execution is not supported.
 */
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock(value = "DSL")
class ComponentsRegistryDSLTest {
    @Test
    fun testOdbcBuildTool() {
        val components = registryDsl {
            component("manager") {
                build {
                    tools {
                        odbc {
                            version = "12"
                        }
                    }
                }
            }
            component("component_commons") {
                build {
                    tools {
                        odbc {
                            version = "11"
                        }
                    }
                }
            }
            component("octopusweb") {
                build {
                    tools {
                        odbc {
                        }
                    }
                }
            }
        }
        assertEquals("12", components.getValue("manager").build.tools.filter { it.buildToolType == BuildToolTypes.ODBC }.map { it as OdbcTool }[0].version)
        assertEquals("11", components.getValue("component_commons").build.tools.filter { it.buildToolType == BuildToolTypes.ODBC }.map { it as OdbcTool }[0].version)
        assertEquals("12.2", components.getValue("octopusweb").build.tools.filter { it.buildToolType == BuildToolTypes.ODBC }.map { it as OdbcTool }[0].version)
    }

    @Test
    fun testProductTypes() {
        val components = registryDsl {
            component("DDD") {
                productType = "PT_K"
                build {
                    tools {
                        database {
                            oracle {
                                version = "[12,)"
                            }
                        }
                    }
                }
                components {
                    component("component-d") {
                    }
                }
            }
            component("TEST_COMPONENT2") {
                build {
                    tools {
                        product {
                            type("PT_C") {
                            }
                        }
                    }
                }
            }
            component("component_db") {
                productType = "PT_D_DB"
                build {
                    tools {
                        product {
                            database {
                                oracle {
                                    version = "[12,)"
                                }
                            }
                        }
                    }
                }
            }
            component("COMPONENT") {
                productType = "PT_D"
                build {
                    tools {
                        product {
                            type("PT_D_DB") {
                            }
                        }
                    }
                }
            }
            component("TS") {
                build {
                    tools {
                        product {
                            type("PT_D") {
                            }
                        }
                    }
                }
            }
        }
        assertEquals(ProductTypes.PT_K, components.getValue("DDD").productType)
        assertEquals(ProductTypes.PT_D_DB, components.getValue("component_db").productType)
        assertEquals(ProductTypes.PT_D, components.getValue("COMPONENT").productType)
        assertNotNull(components.getValue("DDD").subComponents.getValue("component-d"))
    }

    @Test
    fun testComponent() {
        val components = registryDsl {
            component("test-1") {
            }
            component("test-2") {
            }
        }
        assertEquals(2, components.size)
        components.containsKey("test-1")
        components.containsKey("test-2")
    }

    @Test
    fun testBuildSectionOfTheComponent() {
        val components = registryDsl {
            component("test") {
                build {
                    javaVersion = "1.8"
                }
            }
        }
        assertEquals(1, components.size)
        components.containsKey("test")
    }

    @Test
    fun testToolSectionOfTheBuild() {
        val components = registryDsl {
            component("test") {
                build {
                    tools {
                    }
                }
            }
        }
        assertEquals(1, components.size)
        components.containsKey("test")
    }

    @Test
    fun testDatabaseSectionOfTheTool() {
        val components = registryDsl {
            component("test") {
                build {
                    tools {
                        database {
                            oracle {
                                version = "11.2"
                            }
                        }
                    }
                }
            }
        }
        val tools = components.getValue("test").build.tools
        assertEquals(1, tools.size)
        val tool = tools.iterator().next()
        assertTrue(tool is OracleDatabaseTool)
        assertEquals("11.2", (tool as OracleDatabaseTool).version)
    }

    //TODO Add vice versa test when the build section is defined later than version section
    @Test
    fun testOracleDatabaseVersioning() {
        val components = registryDsl {
            component("test") {
                build {
                    tools {
                        database {
                            oracle {
                                version = "11.2"
                            }
                        }
                    }
                }
                version("(1,2)") {
                    build {
                        tools {
                            database {
                                oracle {
                                    version = "12"
                                }
                            }
                        }
                    }
                }
                version("(2,3)") {
                }
            }
        }
        val component = components.getValue("test")
        assertEquals("11.2", (component.build.tools.iterator().next() as OracleDatabaseTool).version)
        assertEquals("11.2", (component.versions.getValue("(2,3)").build.tools.iterator().next() as OracleDatabaseTool).version)
        assertEquals("12", (component.versions.getValue("(1,2)").build.tools.iterator().next() as OracleDatabaseTool).version)
    }

    /**
     * Test [DependenciesDSL] of the component's 'dependencies' DSL .
     */
    @Test
    fun testDependencies() {
        val components = registryDsl {
            component("X") {
                build {
                    dependencies {
                        autoUpdate = true
                    }
                }
            }
            component("Y") {
            }
            component("XY") {
                build {
                    dependencies {
                        autoUpdate = true
                    }
                }
                components {
                    component("XY1") {
                    }
                    component("XY2") {
                        build {
                            dependencies {
                                autoUpdate = false
                            }
                        }
                    }
                    component("XY3") {
                    }
                }
            }
            component("Z") {
                components {
                    component("Z1") {
                        build {
                            dependencies {
                                autoUpdate = true
                            }
                        }
                    }
                    component("Z2") {
                    }
                }
            }
        }
        assertTrue(components.getValue("X").build.dependencies.autoUpdate)
        assertNull(components.getValue("Y").build)
        assertTrue(components.getValue("XY").build.dependencies.autoUpdate)
        assertTrue(components.getValue("XY").subComponents.getValue("XY1").build.dependencies.autoUpdate)
        assertFalse(components.getValue("XY").subComponents.getValue("XY2").build.dependencies.autoUpdate)
        assertTrue(components.getValue("XY").subComponents.getValue("XY3").build.dependencies.autoUpdate)
        assertNull(components.getValue("Z").build)
        assertTrue(components.getValue("Z").subComponents.getValue("Z1").build.dependencies.autoUpdate)
        assertNull(components.getValue("Z").subComponents.getValue("Z2").build)
    }
}