import static org.octopusden.octopus.escrow.BuildSystem.*
import static org.octopusden.octopus.escrow.RepositoryType.MERCURIAL

"octopus-parent" {
    componentOwner = "user1"
    "[1.2.0,)" {
        vcsUrl = "ssh://hg@mercurial/maven-parent"
        groupId = "org.octopusden.octopus"
        artifactId = "octopus-parent"
    }
    jira {
        projectKey = "MPARENT"
    }

}

"jar-osgifier" {
    componentOwner = "user1"
    "$ALL_VERSIONS" {
        groupId = "org.octopusden.octopus.buildsystem"
        artifactId = "jar-osgifier"
        vcsUrl = "ssh://hg@mercurial/jar-osgifier"
    }
    jira {
        projectKey = "SYSTEM"
    }
}

"eclipse-product-exporter" {
    componentOwner = "user1"
    vcsUrl = "ssh://hg@mercurial/eclipse-product-exporter"
    artifactId = "eclipse-product-exporter"
    groupId = "org.octopusden.octopus.eclipse-product-exporter"
    buildSystem = MAVEN
    repositoryType = MERCURIAL
    build {
        javaVersion = "1.7"
    }
    jira {
        projectKey = "SYSTEM"
    }
}

screenshotter {
    componentOwner = "user1"
    "$ALL_VERSIONS" {
        groupId = "org.octopusden.octopus.util"
        artifactId = "screenshotter"
        vcsUrl = "ssh://hg@mercurial/screenshotter"
        buildSystem = MAVEN
        repositoryType = MERCURIAL
        tag = "screenshotter-1.65"
    }
    jira {
        projectKey = "SYSTEM"
    }
}

"DBSchemeManager-client" {
    componentOwner = "user1"
    "$ALL_VERSIONS" {
        groupId = "org.octopusden.octopus.dbsm"
        artifactId = "component_client,component_client-library,dbsm-ant-task,dbsm-maven-plugin,component_client-maven-security-library"
        vcsUrl = "ssh://hg@mercurial/DBSchemeManager-client"
        buildSystem = MAVEN
        repositoryType = MERCURIAL
        tag = 'DBSchemeManager-client-$version'
    }
    jira {
        projectKey = "DBSM"
    }
}

DBSchemeManager {
    componentOwner = "user1"
    "$ALL_VERSIONS" {
        groupId = "org.octopusden.octopus.dbsm"
        artifactId = "dbsm"
        vcsUrl = "ssh://hg@mercurial/DBSchemeManager"
    }
    jira {
        projectKey = "DBSM"
    }
}

"eclipse-core" {
    componentOwner = "user1"
    "[3.5.0]" {
        vcsUrl = "ssh://hg@mercurial/bcomponent-binary-dependencies/eclipse"
        artifactId = "eclipse-core"
        groupId = "org.octopusden.octopus.eclipse"
    }
    jira {
        projectKey = "SYSTEM"
    }
}

osgi {
    componentOwner = "user1"
    "[3.5.0]" {
        vcsUrl = "ssh://hg@mercurial/bcomponent-binary-dependencies/org.eclipse.osgi"
        groupId = "org.octopusden.octopus.org.eclipse.osgi"
        artifactId = "osgi"
    }
    jira {
        projectKey = "SYSTEM"
    }
}

saxon {
    componentOwner = "user1"
    "$ALL_VERSIONS" {
        vcsUrl = "ssh://hg@mercurial/bcomponent-binary-dependencies/saxon"
        groupId = "org.octopusden.octopus.net.sourceforge.saxon"
        buildSystem = MAVEN
        repositoryType = MERCURIAL
    }
    jira {
        projectKey = "SYSTEM"
    }
}

ant {
    componentOwner = "user1"
    "$ALL_VERSIONS" {
        vcsUrl = "ssh://hg@mercurial/bcomponent-binary-dependencies/ant"
        groupId = "org.octopusden.octopus.org.apache.ant"
        buildSystem = MAVEN
        repositoryType = MERCURIAL
    }
    jira {
        projectKey = "SYSTEM"
    }
}

xmlbeans {
    componentOwner = "user1"
    "$ALL_VERSIONS" {
        vcsUrl = "ssh://hg@mercurial/bcomponent-binary-dependencies/xmlbeans"
        artifactId = "xmlbeans"
        groupId = "org.octopusden.octopus.org.apache.xmlbeans"
        buildSystem = MAVEN
        repositoryType = MERCURIAL
    }
    jira {
        projectKey = "SYSTEM"
    }
}

cvsclient {
    componentOwner = "user1"
    "$ALL_VERSIONS" {
        vcsUrl = "ssh://hg@mercurial/bcomponent-binary-dependencies/cvsclient"
        artifactId = "cvsclient"
        groupId = "org.octopusden.octopus.org.netbeans.lib"
        buildSystem = MAVEN
        repositoryType = MERCURIAL
    }
    jira {
        projectKey = "SYSTEM"
    }
}

checkstyle {
    componentOwner = "user1"
    "$ALL_VERSIONS" {
        groupId = "org.octopusden.octopus"
        artifactId = "checkstyle"
        vcsUrl = "ssh://hg@mercurial/checkstyle"
    }
    jira {
        projectKey = "SYSTEM"
    }
}

"missing-licenses-properties" {
    componentOwner = "user1"
    "[1.0]" {
        groupId = "org.octopusden.octopus.licensecontrol"
        artifactId = "missing-licenses"
        buildSystem = PROVIDED
    }
    jira {
        projectKey = "SYSTEM"
    }
}


bcomponent {
    componentOwner = "user1"
    groupId = "org.octopusden.octopus.bcomponent"
    vcsUrl = "ssh://hg@mercurial/bcomponent"

    "[1.12.1-150,1.12.108-490)" {
        groupId = "org.octopusden.octopus.bcomponent"
    }

    "[1.12.108-490,)" {
        tag = '$module-R-$version'
    }

    jira {
        projectKey = "BCOMPONENT"
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service-$fix'
    }

    components {
        "buildsystem-model" {
            componentOwner = "user1"
            groupId = "org.octopusden.octopus.buildsystem.model"
            vcsUrl = "ssh://hg@mercurial/buildsystem-model"
            jira {
                majorVersionFormat = '$major.$minor'
                releaseVersionFormat = '$major.$minor.$service'
                component {
                    versionPrefix = 'Model'
                }
            }
        }

        "buildsystem-mojo" {
            componentOwner = "user1"
            "[1.3, 1.3.159), (1.3.159,)" {
                groupId = "org.octopusden.octopus.mojo"
                artifactId = "buildsystem-maven-plugin"
                vcsUrl = "ssh://hg@mercurial/maven-buildsystem-plugin"
                tag = 'maven-buildsystem-plugin-$version'
                jira {
                    majorVersionFormat = '$major.$minor'
                    releaseVersionFormat = '$major.$minor.$service'
                    component {
                        versionPrefix = 'Mojo'
                    }
                }
            }
        }

        "buildsystem-mojo-new" {
            componentOwner = "user1"
            "[1.3.159]" {
                groupId = "org.octopusden.octopus.mojo"
                artifactId = "buildsystem-maven-plugin"
                vcsUrl = "ssh://hg@mercurial/maven-buildsystem-plugin"
                tag = 'maven-buildsystem-plugin-$version'
            }
        }
    }
}






