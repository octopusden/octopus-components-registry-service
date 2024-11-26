import static org.octopusden.octopus.escrow.BuildSystem.*
import static org.octopusden.octopus.escrow.RepositoryType.*

"db-api" { // pkgj artifacts
    componentOwner = "user1"
    groupId = "org.octopusden.octopus.whiskey.db-api"
    artifactId = "db-api-aggregator-stub" // fake

    jira {
        projectKey = "DDD"
        technical = false
        component { versionPrefix = 'db-api' }
    }
    "[99999,100000]" {
        //fake in order
    }

    buildSystem = PROVIDED

   components{
        "db-p" {
            componentOwner = "user1"
            groupId = "org.octopusden.octopus.whiskey"
            artifactId = "db-p"
            vcsSettings {
                vcsUrl = "git@gitlab:ptddd/db-p.git"
            }

            jira {
                majorVersionFormat = '$major.$minor'
                releaseVersionFormat = '$major.$minor.$service.$fix-$build'
                buildVersionFormat = '$major.$minor.$service.$fix-$build'
                    projectKey = "DDD"
                    component { versionPrefix = 'db-p' }
            }
        }

        ptkdb_api {
            build {
                dependencies {
                    autoUpdate = true
                }
            }
            componentOwner = "user1"
            groupId = "org.octopusden.octopus.ptkmodel2,org.octopusden.octopus.whiskey.kdb.model,org.octopusden.octopus.pt_k_db"
            "[03.51.29.15,)" {
            artifactId = "kdb_xmlmanager_metadata,kdb_table,ptkdb_model,kdb-model-parent"
            repositoryType = CVS
            vcsUrl = 'OctopusSource/OctopusK/Other/models/KDB'
            tag = 'K_$major02_$minor02_$service02_$fix02'
            buildSystem = MAVEN
            }
            jira {
                component { versionPrefix = "ptkdb_api" }
            }
        }

        pt_k_db_api {
            componentOwner = "user1"
            groupId = "org.octopusden.octopus.ptkmodel2,org.octopusden.octopus.whiskey.ptk.model"
            "[03.51.29.15,)" {
                artifactId = "tabedit,k_xmlmanager_metadata,k_table,k_model,ptk-model-parent"
            }
            "(,03.51.29.15)" {
                artifactId = "nothing_see_pt_k_db"
            }
            repositoryType = CVS
            vcsUrl = 'OctopusSource/OctopusK/Other/models/PTK'
            tag = 'K_$major02_$minor02_$service02_$fix02'
            buildSystem = MAVEN
            jira {
                component { versionPrefix = "pt_k_db_api" }
            }
        }

        k_dbjava_api {
            componentOwner = "user1"
            groupId = "org.octopusden.octopus.pt_k_db"
            artifactId = "dbjava"
            vcsUrl = 'ssh://hg@mercurial/products/octopusk/DbJava'
            tag = 'K_$major02_$minor02_$service02_$fix02'
            buildSystem = MAVEN
            jira {
                component { versionPrefix = "k_dbjava_api" }
            }
        }


        pt_k_packages_api {
            componentOwner = "user1"
            groupId = "org.octopusden.octopus.pt_k_db,org.octopusden.octopus.whiskey.ptk.packages,org.octopusden.octopus.ptkmodel2"
            "[03.51.29.15,)" {
                artifactId = "genJava,pt_k_packages,ptk,pt_k_packages_lst,kdb,k_db-parent,ptk-packages-parent"
            }
            "(,03.51.29.15)" {
                artifactId = "nothing_see_k_db_"
            }
            repositoryType = CVS
            vcsUrl = 'OctopusSource/OctopusK/Other/packages'
            tag = 'K_$major02_$minor02_$service02_$fix02'
            buildSystem = MAVEN
            jira {
                component { versionPrefix = "pt_k_packages_api" }
            }
        }

        c_db_api {
            componentOwner = "user1"
            groupId = "org.octopusden.octopus.componentmodel2,org.octopusden.octopus.whiskey.component.model"
            "[03.51.29.15,)" {
                artifactId = "tabedit,component_xmlmanager_metadata,component_table,component_model,component-model-parent"
            }
            "(,03.51.29.15)" {
                artifactId = "nothing_see_c_db"
            }
            repositoryType = CVS
            vcsUrl = 'OctopusSource/Octopus/Other/models/Component'
            tag = 'TEST_COMPONENT2_$major02_$minor02_$service02_$fix02'
            buildSystem = MAVEN
            jira {
                component { versionPrefix = "c_db_api" }
            }
        }

        component_db {
            componentOwner = "user1"
            groupId = "org.octopusden.octopus.component_db"
            artifactId = "dbjava"
            vcsUrl = 'ssh://hg@mercurial/products/octopuscomponent/DbJava'
            tag = 'TEST_COMPONENT2_$major02_$minor02_$service02_$fix02'
            buildSystem = MAVEN
            jira {
                component { versionPrefix = "component_db" }
            }
        }

        component_packages_api {
            componentOwner = "user1"
            groupId = "org.octopusden.octopus.component_db,org.octopusden.octopus.whiskey.component.packages,org.octopusden.octopus.componentmodel2"
            "[03.51.29.15,)" {
                artifactId = "genJava,component_packages,component,component_packages_lst,component_db-parent,component-packages-parent"
            }
            "(,03.51.29.15)" {
                artifactId = "nothing_see_c_db_"
            }
            repositoryType = CVS
            vcsUrl = 'OctopusSource/Octopus/Other/packages'
            tag = 'TEST_COMPONENT2_$major02_$minor02_$service02_$fix02'
            buildSystem = MAVEN
            jira {
                component { versionPrefix = "component_packages_api" }
            }
        }
    }
}

// Whiskey components
pt_k_db {
    componentOwner = "user1"
    groupId = "org.octopusden.octopus.ptkmodel2"
    jira {
        projectKey = "DDD"
        technical = true
        lineVersionFormat = '$major02.$minorC'
        majorVersionFormat = '$major02.$minorC.$serviceC'
        releaseVersionFormat = '$major02.$minor02.$service02.$fix02'
        buildVersionFormat = '$major02.$minor02.$service02.$fix02-$build04'
        component {
            versionPrefix = 'db'
            versionFormat = '$baseVersionFormat-$versionPrefix'
        }
    }

   "[03.51.29.15,)" {
        groupId = "org.octopusden.octopus.ptkmodel2.dummy"
        if( System.env.WHISKEY?.contains("ESCROW") ) {
            buildSystem = WHISKEY
            build {
                requiredTools = "BuildEnv,Whiskey,PowerBuilderCompiler170"
            }
        } else {
            buildSystem = PROVIDED
            vcsSettings {
                externalRegistry = "pt_k_db"
            }
        }
    }
   "[03.49.30.56],[03.50.30,03.51.29.15)" {
        if( System.env.WHISKEY?.contains("ESCROW") ) {
	        buildSystem = WHISKEY
	        build {
	            requiredTools = "BuildEnv,Whiskey,PowerBuilderCompiler170"
	        }
        } else {
	        buildSystem = PROVIDED
	        vcsSettings {
	            externalRegistry = "pt_k_db"
	        }
        }
    }
    "(,03.49.30.56),(03.49.30.56,03.50.30)" {
        vcsSettings {
            externalRegistry = "pt_k_db"
        }
        buildSystem = PROVIDED
    }
    distribution {
        explicit = false
        external = true
    }
}

DDD {
    componentOwner = "user1"
    jira {
        projectKey = "DDD"
        lineVersionFormat = '$major02.$minorC'
        majorVersionFormat = '$major02.$minorC.$serviceC'
        releaseVersionFormat = '$major02.$minor02.$service02.$fix02'
        buildVersionFormat = '$major02.$minor02.$service02.$fix02-$build'
    }

    "[03.49.30.56],[03.50.30,)" {
        if( System.env.WHISKEY?.contains("ESCROW") ) {
	        buildSystem = WHISKEY
	        build {
	            requiredTools = "BuildEnv,Whiskey,PowerBuilderCompiler170"
	        }
        } else {
	        vcsSettings {
	            externalRegistry = "ptk"
	        }
	        buildSystem = PROVIDED
	    }
	}
    "(,03.49.30.56),(03.49.30.56,03.50.30)" {
        vcsSettings {
            externalRegistry = "ptk"
        }
        buildSystem = PROVIDED
    }
    groupId = "org.octopusden.octopus.wk2"

    distribution {
        explicit = false
        external = true
    }
}

componentc_db {
    componentOwner = "user1"
    groupId = "org.octopusden.octopus.componentmodel2,org.octopusden.octopus.componentmodel"
    jira {
        projectKey = "COMPONENTC"
        technical = true
        lineVersionFormat = '$major02.$minorC'
        majorVersionFormat = '$major02.$minorC.$serviceC'
        releaseVersionFormat = '$major02.$minor02.$service02.$fix02'
        buildVersionFormat = '$major02.$minor02.$service02.$fix02-$build04'
        displayName = "DB"
        component {
            versionPrefix = 'db'
            versionFormat = '$baseVersionFormat-$versionPrefix'
        }
    }

    "[03.51.29.15,)" {
        groupId = "org.octopusden.octopus.componentmodel2.dummy"
        if( System.env.WHISKEY?.contains("ESCROW") ) {
            buildSystem = WHISKEY
            build {
                requiredTools = "BuildEnv,Whiskey,PowerBuilderCompiler170"
            }
        } else {
                vcsSettings {
                externalRegistry = "componentc_db"
            }
            buildSystem = PROVIDED
        }
    }
    "[03.49.30.56],[03.50.30,03.51.29.15)" {
        if( System.env.WHISKEY?.contains("ESCROW") ) {
	        buildSystem = WHISKEY
	        build {
	            requiredTools = "BuildEnv,Whiskey,PowerBuilderCompiler170"
	        }
        } else {
	            vcsSettings {
	            externalRegistry = "componentc_db"
	        }
	        buildSystem = PROVIDED
	    }
	}
    "(,03.49.30.56),(03.49.30.56,03.50.30)" {
            vcsSettings {
            externalRegistry = "componentc_db"
        }
        buildSystem = PROVIDED
    }
    distribution {
        explicit = false
        external = true
    }
}

//explicit
COMPONENT_C {
    componentDisplayName = "Component display name"
    componentOwner = "user6"
    releaseManager = "user6"
    securityChampion = "user7"
    jira {
        projectKey = "COMPONENT_C"
        lineVersionFormat = '$major02.$minorC'
        majorVersionFormat = '$major02.$minorC.$serviceC'
        releaseVersionFormat = '$major02.$minor02.$service02.$fix02'
        buildVersionFormat = '$major02.$minor02.$service02.$fix02-$build'
    }

    "[03.49.30.56],[03.50.30,)" {
        if( System.env.WHISKEY?.contains("ESCROW") ) {
	        buildSystem = WHISKEY
	        build {
	            requiredTools = "BuildEnv,Whiskey,PowerBuilderCompiler170"
	        }
        } else {
	        vcsSettings {
	            externalRegistry = "componentc"
	        }
	        buildSystem = PROVIDED;
	    }
    }
    "(,03.49.30.56),(03.49.30.56,03.50.30)" {
        vcsSettings {
            externalRegistry = "componentc"
        }
        buildSystem = PROVIDED;
    }
    groupId = "org.octopusden.octopus.componentc"
    distribution {
        explicit = true
        external = true
    }
}

// Stand Alone Pin Management
 project_db {
    componentOwner = "user1"
    jira {
        projectKey = "projectKey"
        lineVersionFormat = '$major02.$minorC'
        majorVersionFormat = '$major02.$minorC.$serviceC'
        releaseVersionFormat = '$major02.$minor02.$service02.$fix02'
        buildVersionFormat = '$major02.$minor02.$service02.$fix02-$build'
        component {
            versionPrefix = 'db'
            versionFormat = '$baseVersionFormat-$versionPrefix'
        }
    }
    vcsSettings {
        externalRegistry = " project_db"
    }
    buildSystem = PROVIDED
    groupId = "org.octopusden.octopus. project_db"
    distribution {
        explicit = false
        external = true
    }
}
// Stand Alone Pin Management
sa_component {
    componentDisplayName = "Standalone PIN Management"
    componentOwner = "tkachan"
    releaseManager = "tkachan"
    securityChampion = "tkachan"
    jira {
        projectKey = "sa_component"
        lineVersionFormat = '$major02.$minorC'
        majorVersionFormat = '$major02.$minorC.$serviceC'
        releaseVersionFormat = '$major02.$minor02.$service02.$fix02'
        buildVersionFormat = '$major02.$minor02.$service02.$fix02-$build'
    }
    vcsSettings {
        externalRegistry = "pm"
    }
    buildSystem = PROVIDED
    groupId = "org.octopusden.octopus.sa_component"
    distribution {
        explicit = true
        external = true
    }
}

component_db {
    componentOwner = "user1"
    groupId = "org.octopusden.octopus.component_db"
    jira {
        projectKey = "COMPONENT"
        lineVersionFormat = '$major02.$minorC'
        majorVersionFormat = '$major02.$minorC.$serviceC'
        releaseVersionFormat = '$major02.$minor02.$service02.$fix02'
        buildVersionFormat = '$major02.$minor02.$service02.$fix02-$build'
        displayName = "DB"
        technical = true
        component {
            versionPrefix = 'db'
            versionFormat = '$baseVersionFormat-$versionPrefix'
        }
    }
    if( System.env.WHISKEY?.contains("ESCROW") ) {
        buildSystem = WHISKEY
        build {
            requiredTools = "BuildEnv,Whiskey,PowerBuilderCompiler170"
        }
    } else {
	    buildSystem = PROVIDED
	    vcsSettings {
	        externalRegistry = "component_db"
	    }
	}
    distribution {
        explicit = false
        external = true
    }
}

DM {
    componentDisplayName = "componentDisplayName"
    componentOwner = "svol"
    releaseManager = "svol"
    securityChampion = "svol"
    groupId = "org.octopusden.octopus.dwh"
    jira {
        projectKey = "COMPONENT"
        lineVersionFormat = '$major02.$minorC'
        majorVersionFormat = '$major02.$minorC.$serviceC'
        releaseVersionFormat = '$major02.$minor02.$service02.$fix02'
        buildVersionFormat = '$major02.$minor02.$service02.$fix02-$build'
    }
    if( System.env.WHISKEY?.contains("ESCROW") ) {
        buildSystem = WHISKEY
        build {
            requiredTools = "BuildEnv,Whiskey,PowerBuilderCompiler170"
        }
    } else {
	    buildSystem = PROVIDED;

	    vcsSettings {
	        externalRegistry = "dwh"
	    }
	}

    distribution {
        explicit = true
        external = true
    }
}
