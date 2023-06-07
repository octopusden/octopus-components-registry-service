import static org.octopusden.octopus.escrow.BuildSystem.*


"tool" {
    componentOwner = "user2"
    componentDisplayName = "White Labeling Tool"
    groupId = "org.octopusden.octopus.tools.wl"
    vcsSettings {
        vcsUrl = "ssh://git@git.someorganisation.com/releng/wl-tool.git"
    }
    jira {
        projectKey = "RELENG"
        majorVersionFormat = '$major'
        releaseVersionFormat = '$major.$minor'
        component {
            versionPrefix = 'wl-tool'
        }
    }
    distribution {
        explicit = true
        external = false
    }
}

"component_commons" {
    componentDisplayName = "Platform Commons"
    componentOwner = "user"
    jira {
        projectKey = "PLCOMMONS"
    }
    vcsUrl = 'git@gitlab:platform/component_commons.git'
    buildSystem = GRADLE
    artifactId = "component_commons"
    groupId = "org.octopusden.octopus.platform"
    tag = 'component_commons-$version'


    build {
        javaVersion = 1.8
        gradleVersion = "4.8"
        requiredProject = false
        dependencies {
            autoUpdate = true
        }
    }

    "[1.0,1.0.336)" {
        build {
            buildTasks = "assemble -x checkstyleMain -x findBugsMain -x pmdMain"
        }
    }

    "[1.0.336, 1.0.344]" {
        build {
            gradleVersion = "4.0"
            buildTasks = "assemble -x checkstyleMain -x findBugsMain -x pmdMain"
        }
    }
    "(1.0.344,1.0.570)" {
        build {
            buildTasks = "assemble -x checkstyleMain -x spotBugsMain -x pmdMain"
        }
    }
    "[1.0.570,)" {
        build {
            buildTasks = "assemble"
        }
    }

    distribution {
        explicit = false
        external = true
    }
}

"monitoring" {
    componentOwner = "user1"
    vcsUrl = 'ssh://hg@mercurial/server/monitoring'
    groupId = "org.octopusden.octopus.server"
    artifactId = "monitoring-app,monitoring-core,monitoring-distribution,monitoring-gates,monitoring-jdbc-gate,monitoring-jmx-gate," +
            "monitoring-listeners,monitoring-processors,monitoring-snmp-gate,monitoring-bootstrap,monitoring-docs,monitoring-file-gate," +
            "monitoring-gates-shared,monitoring-rest-gate," +
            "monitoring-component-db-agent,monitoring-plugins,monitoring-remoting,monitoring-remoting-api,monitoring-remoting-backend,monitoring-history-db,monitoring-history-db-plugin," +
            "monitoring-history-db-persistence,monitoring-database-gate-service,monitoring-history-db-liquibase,monitoring-history-db-dao,monitoring-history-db-persistence-JPA,monitoring-apptemplate," +
            "monitoring-ui-parent,monitoring-ui,monitoring-ui-apptemplate," +
            "monitoring-parent"
    tag = 'monitoring-$version'
    jira {
        projectKey = "HMON"
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
    }
    build {
        javaVersion = "1.8"
    }
    distribution {
        explicit = false
        external = true
    }
}

app {
    build {
        dependencies {
            autoUpdate = true
        }
    }
    componentDisplayName = "Application Server"
    componentOwner = "user3"
    releaseManager = "user3"
    securityChampion = "user3"
    groupId = "org.octopusden.octopus.server"
    artifactId = "server"
    jira {
        projectKey = "AS"
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
        component {
            versionFormat = '$versionPrefix.$baseVersionFormat'
        }
    }
    distribution {
        explicit = true
        external = true
        GAV='org.octopusden.octopus.distribution.server:app:zip:aix7_1,org.octopusden.octopus.distribution.server:app:zip:aix7_2,org.octopusden.octopus.distribution.server:app:zip:rhel6-linux-i386-nojdk,org.octopusden.octopus.distribution.server:app:zip:rhel6-linux-x64-nojdk,org.octopusden.octopus.distribution.server:app:zip:rhel7-linux-x64-nojdk,org.octopusden.octopus.distribution.server:app:zip:rhel8-linux-x64-nojdk,org.octopusden.octopus.distribution.server:app:zip:solaris11-sparc-nojdk,org.octopusden.octopus.distribution.server:app:zip:windows-x64-nojdk'
    }
    "[1.5,1.6)" {
        vcsSettings {
            vcsUrl = 'ssh://hg@mercurial/server/release'
            tag = 'server-$version'
            branch = "1.5"
        }
        build {
            javaVersion = "1.7"
        }
    }
    "[1.6,1.6.2000)" {
        vcsSettings {
            vcsUrl = 'ssh://hg@mercurial/server/release'
            tag = 'server-$version'
            branch = "1.6"
        }
        build {
            javaVersion = "1.8"
        }
    }
    "[1.6.2000,1.6.2521),(1.6.2611,1.7)" {
        buildSystem = PROVIDED
        vcsSettings {
            branch = "1.6"
            core {
                vcsUrl = "ssh://hg@mercurial/server/core"
                tag = 'core-$version'
            }
            docs {
                vcsUrl = "ssh://hg@mercurial/server/docs"
                tag = 'docs-$version'
            }
            installer {
                vcsUrl = "ssh://hg@mercurial/server/installer"
                tag = 'installer-$version'
            }
            legacy {
                vcsUrl = "ssh://hg@mercurial/server/legacy"
                tag = 'legacy-$version'
            }
            "release-script" {
                vcsUrl = 'ssh://hg@mercurial/server/release'
                tag = 'server-$version'
            }
        }
	    distribution {
	    	GAV="file:///as-1.6"
	    }
    }
    "[1.6.2521,1.6.2611]" {
        groupId = "org.octopusden.octopus.distribution.server"
        artifactId = "app"
        buildSystem = GRADLE
        vcsSettings {
            "server-distribution-vcs" {
                repositoryType = "GIT"
                vcsUrl = "git@gitlab:platform/server/server.git"
                branch = "1.6"
            }
        }
        build {
            javaVersion = "1.8"
            buildTasks = "publishToMavenLocal"
        }
    }
    "[1.7,2)" {
        groupId = "org.octopusden.octopus.server.server-distribution,org.octopusden.octopus.server.server-distribution.installer"
        artifactId = "installer,app"
        buildSystem = GRADLE
        vcsSettings {
            "server-distribution-vcs" {
                repositoryType = "GIT"
                vcsUrl = "git@gitlab:platform/server/server.git"
                branch = "master"
            }
        }
        jira {
            displayName = "Application Server"
        }
        build {
            javaVersion = "1.8"
            buildTasks = "publishToMavenLocal"
        }
    }

    components {
        "server-distribution" {
            groupId = "org.octopusden.octopus.server.server-distribution,org.octopusden.octopus.server.server-distribution.installer"
            artifactId = "installer,app"
            buildSystem = GRADLE
            "[100,1000]" {
                vcsSettings {
                    "server-distribution-vcs" {
                        repositoryType = "GIT"
                        vcsUrl = "git@gitlab:platform/server/server.git"
                    }
                }
                jira {
                    displayName = "Application Server"
                    component {
                        versionPrefix = 'release'
                    }
                }
                build {
                    javaVersion = "999999999999999999999"
                    buildTasks = "publishToMavenLocal"
                }
            }
            distribution {
                explicit = true
                external = false
            }
        }
        "ansible" {
            build {
                dependencies {
                    autoUpdate = false
                }
            }
            groupId = "org.octopusden.octopus.server.server-distribution.installer"
            artifactId = "ansible-deploy"
            buildSystem = GRADLE
            "[1.7,2)" {
                vcsSettings {
                    "server-distribution-vcs" {
                        repositoryType = "GIT"
                        vcsUrl = "git@gitlab:ansible-galaxy-ow/deploy-ansible.git"
                    }
                }
                jira {
                    displayName = "Application Server"
                    component {
                        versionPrefix = 'ansible'
                    }
                }
                build {
                    javaVersion = "1.8"
                    buildTasks = "publishToMavenLocal"
                }
            }
            distribution {
                explicit = false
            }
        }
        "installer" {
            "[1.5,1.6),[1.6.2521,1.6.2611]" {
                vcsUrl = 'ssh://hg@mercurial/server/installer'
                groupId = "org.octopusden.octopus.server"
                artifactId = "installer"
                tag = 'installer-$version'
                jira {
                    displayName = "Application Server Installer"
                    component {
                        versionPrefix = 'installer'
                    }
                }
            }
            distribution {
                explicit = false
            }
        }
        "server" {
            groupId = "org.octopusden.octopus.server,org.octopusden.octopus.server.lib,org.octopusden.octopus.component_container,org.octopusden.octopus.server.monitoring"
            artifactId = "release-aggregator,component_container-xsd,monitoring,component_containerclient,component_container," +
                "web_console,security-certificates,lib,server-parent,lib-lib,launcher"
            buildSystem = MAVEN
            jira {
                displayName = "Core"
                technical=true
                component {
                    versionPrefix = 'core'
                }
            }
            vcsSettings {
                "server-vcs" {
                    vcsUrl = "ssh://hg@mercurial/server/core"
                    branch = "default"
                    tag = 'core-$version'
                }
            }
            "[1.6.2410]" {
                 vcsSettings {
                     "server-vcs" {
                         vcsUrl = "ssh://hg@mercurial/server/core"
                         tag = 'AS-2884'
                     }
                 }
            }

            "[1.6,1.6.2410),(1.6.2410,1.7)" {
            }
            "[1.7, 2)" {
                build {
                    javaVersion = "1.8"
                }
                vcsSettings {
                    "server-vcs" {
                        tag = 'server-$version'
                    }
                }
            }
            distribution {
                explicit = false
            }
        }
        "legacy" {
            groupId = "org.octopusden.octopus.server.legacy"
            artifactId = "legacy-aggregator,legacy-aix,legacy-linux-i386,legacy-linux-x64,legacy-solaris-sparc,legacy-solaris11-x64,legacy-windows-i386,legacy-windows-x64"
            buildSystem = MAVEN
            jira {
                displayName = "Legacy"
                technical=true
                component {
                    versionPrefix = 'legacy'
                }
            }
            vcsSettings {
                "legacy-vcs" {
                    vcsUrl = "ssh://hg@mercurial/server/legacy"
                    branch = "default"
                }
            }
            "[1.6,1.6.2000),[1.6.2521,1.6.2611]" {
                vcsSettings {
                    "legacy-vcs" {
                        vcsUrl = "ssh://hg@mercurial/server/legacy"
                        tag = 'legacy-$version'
                    }
                }
            }
            "[1.7,)" {
                build {
                    javaVersion = "1.8"
                }
            }
            distribution {
                explicit = false
            }
        }
        "app-java-service" {
            groupId = "org.octopusden.octopus.server"
	    artifactId = "java-service"
            buildSystem = GRADLE
            jira {
                displayName = "Java Service"
                technical=true
                component {
                    versionPrefix = 'javaservice'
                }
            }
            vcsSettings {
                "app-java-service-vcs" {
                    vcsUrl = "ssh://hg@mercurial/server/javaservice"
                    branch = "default"
		    tag = 'app-java-service-$version'
                }
            }
            build {
                javaVersion = "1.8"
            }
            distribution {
                explicit = false
            }
        }

        "test_comp" {
            groupId = "org.octopusden.octopus.server.apache"
	    artifactId = "test_comp"
            buildSystem = MAVEN
                vcsSettings {
                    "apache-common" {
                        vcsUrl = "ssh://hg@mercurial/server/apache/mod_security"
                        tag = 'test_comp-$version'
                        branch = "apache-2.4"
                    }
                }

            "[2.4.217,)" {
                vcsSettings {
                    "apache-common" {
                        vcsUrl = "ssh://hg@mercurial/server/apache/mod_security"
                        tag = 'test_comp-$version'
                        branch = "apache-2.4"
                    }
                }

            }
            "[2.4,2.4.217)" {
                vcsSettings {
                    "apache-common" {
                        vcsUrl = "ssh://hg@mercurial/server/apache/mod_security"
                        tag = 'mod-$version'
                        branch = "apache-2.4"
                    }
                }

            }

            jira {
                displayName = "Apache Mod Security CRS"
                technical=true
                component {
                    versionPrefix = 'mod'
                }
            }
            distribution {
                explicit = false
            }
        }
        "app-apache" {
            groupId = "org.octopusden.octopus.server.apache,org.octopusden.octopus.server.apache2"
            artifactId = "apache-conf-parent|apache-user-conf|apache-conf-javalib"
            buildSystem = MAVEN
            "[1.7, 2)" {
                vcsSettings {
                    "apache-common" {
                        vcsUrl = "ssh://hg@mercurial/server/apache/apache-common-build"
                        branch = "default"
                    }
                }
                jira {
                    displayName = "Apache"
                    technical=true
                    component {
                        versionPrefix = 'apache'
                    }
                }
            }
            "[1.6, 1.7)" {
                vcsSettings {
                    "apache-common" {
                        vcsUrl = "ssh://hg@mercurial/server/apache/apache-common-build"
                        branch = "1.6"
                    }
                }
                jira {
                    displayName = "Apache"
                    technical=true
                    component {
                        versionPrefix = 'apache'
                    }
                }
            }
            distribution {
                explicit = false
            }
        }
        "app-apache-native" {
            componentOwner = "user1"
            groupId = "org.octopusden.octopus.server.apache2"
            buildSystem = PROVIDED
            jira {
                displayName = "Apache"
                technical=true
                component {
                    versionPrefix = 'apache-native'
                }
            }
            "[1.7, 2)" {
            artifactId = "app-apache-rhel7-x64-2.4|app-apache-rhel6-x64-2.4|app-apache-rhel6-i386-2.4|app-apache-aix-7.1-2.4|app-apache-aix-7.2-2.4|app-apache-solaris11-sparc-2.4|apache-rhel7-x64-2.4-app|apache-rhel6-x64-2.4-app|apache-rhel6-i386-2.4-app|apache-aix-7.1-2.4-app|apache-aix-7.2-2.4-app|apache-solaris11-sparc-2.4-app"
                vcsSettings {
                    "apache-common" {
                        vcsUrl = "ssh://hg@mercurial/server/apache/apache-common-build"
                        branch = "default"
                        tag = 'app-apache-$version'
                    }
                }
            }
            "[1.6, 1.7)" {
            artifactId = "app-apache-rhel7-x64-2.4|app-apache-rhel6-x64-2.4|app-apache-rhel6-i386-2.4|app-apache-aix-7.1-2.4|app-apache-aix-7.2-2.4|app-apache-solaris11-sparc-2.4|app-apache-solaris10-sparc-2.4|app-apache-aix-6.1-2.4|app-apache-win32-2.4"
                vcsSettings {
                    "apache-common" {
                        vcsUrl = "ssh://hg@mercurial/server/apache/apache-common-build"
                        branch = "1.6"
                        tag = 'app-apache-$version'
                    }
                }
            }
            distribution {
                explicit = false
            }
        }
        "jdk" {
            componentOwner = "user1"
            groupId = "org.octopusden.octopus.server.jdk"
            buildSystem = PROVIDED
            jira {
                displayName = "JDK"
                technical=true
                component {
                    versionPrefix = 'jdk'
                }
            }
            "(7,8)" {
                vcsSettings {
                    vcsUrl = "ssh://hg@mercurial/server/jdk"
                    tag = 'jdk-$version'
                }
            }
            "(8,9)" {
            }
            "(1.6,2)" {
                vcsSettings {
                    tag = '$module-$version'
                    vcsUrl = "ssh://hg@mercurial/server/jdk"
                }
            }
            distribution {
                explicit = false
            }
        }
        "app-openjdk" {
            componentOwner = "user1"
            groupId = "org.octopusden.octopus.server.openjdk"
            buildSystem = PROVIDED
            jira {
                displayName = "OpenJDK"
                technical=true
                component {
                    versionPrefix = 'openjdk'
                }
            }
            "(1.6,2)" {
                vcsSettings {
                    tag = '$module-$version'
                    vcsUrl = "ssh://hg@mercurial/server/jdk"
                }
            }
            distribution {
                explicit = false
            }
        }

        "docs" {
            componentOwner = "user1"
            groupId = "org.octopusden.octopus.server"
            artifactId = "docs-singlenode,docs-magent,docs-mdiststore,docs"
            vcsSettings {
                vcsUrl = 'ssh://git@github.com/octopusden/server/docs'
                tag = 'docs-$version'
            }
            jira {
                displayName = "Documentation"
                technical=true
                component {
                    versionPrefix = 'docs'
                }
            }
            "[1.5,1.6)" {
                artifactId = "docs"
                vcsSettings {
                    branch = "1.6"
                }
            }
            "[1.6,1.6.2000),[1.6.2521,1.6.2611]" {
                vcsSettings {
                    branch = "1.6"
                }
            }
            "[1.7,)" {
                vcsSettings {
                    branch = "default"
                    tag = 'docs-$version'
                }
            }
            distribution {
                explicit = false
            }
        }
    }
}
component_proxy {
    componentOwner = "user1"
    jira {
        projectKey = "PK5"
    }

    "[1.0,)" {
        vcsUrl = 'ssh://git@github.com/octopusden/wcomponent/wsr-proxy.git'
        artifactId = "artifact_id"
        groupId = "org.octopusden.octopus.octopusweb.wsrproxy"
        tag = 'wsr-proxy-$version'
    }

    build {
        javaVersion = 1.8
        requiredProject = false
    }

    distribution {
        explicit = false
        external = true
    }
}
