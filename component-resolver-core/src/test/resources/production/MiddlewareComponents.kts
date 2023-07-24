import org.octopusden.octopus.components.registry.dsl.component

component("component_commons") {
    build {
        tools {
            product {
                type("PT_K") {
                }
            }
        }
    }
}

component("app") {
    build {
        tools {
            database {
                oracle {
                    version = "11.2"
                }
            }
            product {
                type("PT_K") {
                    version = "03.49"
                }
            }
        }
    }
    version("[1.7,2)") {
        escrow {
            gradle {
                includeTestConfigurations = true
                includeConfigurations = listOf("1", "2")
                includeConfiguration("a")
                includeConfiguration("b")
            }
            buildTask = "build"
        }
        build {
            tools {
                database {
                    oracle {
                        version = "12"
                    }
                }
                product {
                    type("PT_K") {
                        version = "03.50"
                    }
                }
            }
        }
    }
}

component("monitoring") {
    build {
        tools {
            database {
                oracle {
                    version = "11.2"
                }
            }
            product {
                type("PT_K") {
                    version = "03.49"
                }
            }
        }
    }
}


component("legacy") {
    build {
    }
}

component("server") {
    build {
        dependencies {
            autoUpdate = false
        }
    }
}
