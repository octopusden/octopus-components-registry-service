import static BuildSystem.MAVEN
import static BuildSystem.PROVIDED
import static VCS.CVS
import static VCS.MERCURIAL

DEFAULT_TAG = '$module-$version'
final ANY_ARTIFACT = /[\w-\.]+/
final ALL_VERSIONS = "(,0),[0,)"

enum BuildSystem {
    BS2_0,
    MAVEN,
    PROVIDED
}

enum VCS {
    CVS,
    MERCURIAL
}

Defaults {
    repositoryType = MERCURIAL
    buildSystem = MAVEN;
    tag = DEFAULT_TAG;
    artifactId = ANY_ARTIFACT
    build {
        javaVersion = "1.7"
        mavenVersion = "LATEST"
        gradleVersion = "LATEST"
    }
}

"octopus-parent" {
    componentOwner = "user1"
    deprecated = true

    "[1.2.0,)" {
        vcsUrl = "ssh://hg@mercurial/maven-parent"
        groupId = "org.octopusden.octopus"
        artifactId = "octopus-parent"
    }
}

"buildsystem-model" {
    componentOwner = "user1"
    "$ALL_VERSIONS" {
        groupId = "org.octopusden.octopus.buildsystem.model"
        vcsUrl = "ssh://hg@mercurial/buildsystem-model"
    }
}

"buildsystem-mojo" {
    componentOwner = "user1"
    "$ALL_VERSIONS" {
        groupId = "org.octopusden.octopus.mojo"
        artifactId = "buildsystem-maven-plugin"
        vcsUrl = "ssh://hg@mercurial/maven-buildsystem-plugin"
        buildSystem = MAVEN
        repositoryType = MERCURIAL
        tag = 'maven-buildsystem-plugin-$version'
    }
}

"jar-osgifier" {
    componentOwner = "user1"
    "$ALL_VERSIONS" {
        groupId = "org.octopusden.octopus.buildsystem"
        artifactId = "jar-osgifier"
        vcsUrl = "ssh://hg@mercurial/jar-osgifier"
    }
}

"eclipse-product-exporter" {
    componentOwner = "user1"
    "$ALL_VERSIONS" {
        vcsUrl = "ssh://hg@mercurial/eclipse-product-exporter"
        artifactId = "eclipse-product-exporter"
        groupId = "org.octopusden.octopus.eclipse-product-exporter"
        buildSystem = MAVEN
        repositoryType = MERCURIAL
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
}

DBSchemeManager - client {
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
}

osgi {
    componentOwner = "user1"
    "[3.5.0]" {
        vcsUrl = "ssh://hg@mercurial/bcomponent-binary-dependencies/org.eclipse.osgi"
        groupId = "org.octopusden.octopus.org.eclipse.osgi"
        artifactId = "osgi"
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
}

ant {
    componentOwner = "user1"
    "$ALL_VERSIONS" {
        vcsUrl = "ssh://hg@mercurial/bcomponent-binary-dependencies/ant"
        groupId = "org.octopusden.octopus.org.apache.ant"
        buildSystem = MAVEN
        repositoryType = MERCURIAL
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
}

checkstyle {
    componentOwner = "user1"
    "$ALL_VERSIONS" {
        groupId = "org.octopusden.octopus"
        artifactId = "checkstyle"
        vcsUrl = "ssh://hg@mercurial/checkstyle"
    }
}

bcomponent {
    componentOwner = "user1"
    build {
        javaVersion = "1.7"
    }


    "[1.12.1-150,1.12.108-490)" {
        vcsUrl = "ssh://hg@mercurial/bcomponent"
        groupId = "org.octopusden.octopus.bcomponent"
    }

    "[1.12.108-490,)" {
        vcsUrl = "ssh://hg@mercurial/bcomponent"
        groupId = "org.octopusden.octopus.bcomponent"
        tag = '$module-R-$version'
    }
    jira {
        projectKey = "BCOMPONENT"
    }
}


"test-maven-release" {
    componentOwner = "user1"
    "(,7),(8,)" {
        groupId = "org.octopusden.octopus.test"
        artifactId = "maven-dependencyconflict-plugin"
        buildSystem = PROVIDED
        jiraProjectKey = "MEOW"
        jiraMajorVersionFormat = '$major.$minor'
        jiraReleaseVersionFormat = '$major.$minor.$service'
    }
}

test {
    componentOwner = "user1"
    "(7,8)" {
        groupId = "org.octopusden.octopus.test"
        buildSystem = BuildSystem.BS2_0;
        tag = 'TEST-ESCROW-R-$cvsCompatibleVersion'
        build {
            javaVersion = "1.7"
            mavenVersion = "LATEST"
            gradleVersion = "LATEST"
        }
    }
}


"test-project" {
    componentOwner = "user1"
    "[10,12)" {
        groupId = "org.octopusden.octopus.test"
        artifactId = "test-project"
        vcsUrl = "ssh://hg@mercurial/test-project"
    }
}

"provided-test-dependency" {
    componentOwner = "user1"
    "(,7),(8,)" {
        groupId = "org.octopusden.octopus.test"
        artifactId = "commoncomponent"
        buildSystem = PROVIDED
    }
}

"test-cvs-maven" {
    componentOwner = "user1"
    "[3.1]" {
        groupId = "org.octopusden.octopus.test"
        artifactId = "test-cvs-maven-parent,test-cvs-maven-module1"
        vcsUrl = "back/build/test/sources/test-maven"
        repositoryType = CVS
        tag = '$module-$cvsCompatibleVersion'
        buildFilePath = "test-cvs-maven-parent"
    }

    "[3.0]" {
        groupId = "org.octopusden.octopus.test"
        artifactId = "test-cvs-maven-parent,test-cvs-maven-module1"
        vcsUrl = "back/build/test/sources/test-maven"
        repositoryType = CVS
        tag = '$module-$cvsCompatibleVersion'
    }
}

"escrow-generator" {
    componentOwner = "user1"
    vcsUrl = "ssh://hg@mercurial/escrow-generator"
    groupId = "org.octopusden.octopus.escrow"
    jira {
        projectKey = "ECOMPONENT"
        majorVersionFormat = '$major'
        releaseVersionFormat = '$major.$minor'
    }
}

"maven-crm-plugin" {
    componentOwner = "user1"
    vcsUrl = "ssh://hg@mercurial/maven-crm-plugin"
    groupId = "org.octopusden.octopus.ci-its"
    artifactId = "maven-crm-plugin"
    tag = 'maven-crm-plugin-R-$version'
    jira {
        projectKey = "MCOMPONENT"
        majorVersionFormat = '$major'
        releaseVersionFormat = '$major.$minor'
    }
}

"jira-parent" {
    componentOwner = "user1"
    vcsUrl = "ssh://hg@mercurial/jira-parent"
    groupId = "org.octopusden.octopus.jira"
    artifactId = "jira-parent"
    jira {
        projectKey = "JBOM"
    }
}

"ci-its" {
    componentOwner = "user1"
    vcsUrl = "ssh://hg@mercurial/ci-its"
    groupId = "org.octopusden.octopus.ci-its"
    artifactId = "ci-its-api|ci-jira|jira-relnotes-dto|ci-teamcity|version-helper|jira-its|ci-its|ci-crm"
    tag = 'ci-its-R-$version'
    jira {
        projectKey = "PK1"
        majorVersionFormat = '$major'
        releaseVersionFormat = '$major.$minor'
    }
}

"missing-licenses-properties" {
    componentOwner = "user1"
    "[1.0]" {
        groupId = "org.octopusden.octopus.licensecontrol"
        artifactId = "missing-licenses"
        buildSystem = PROVIDED
    }
}

"escrow-test-project" {
    componentOwner = "user1"
    "(,7),(8,)" {
        groupId = "org.octopusden.octopus.test"
        artifactId = "escrow-test-project"
        buildSystem = MAVEN
        vcsUrl = "ssh://hg@mercurial/test/escrow-test-project"
    }
}





"provided-test-archive" {
    componentOwner = "user1"
    "[1.0],[2.0],[3.0]" {
        groupId = "org.octopusden.octopus.escrow.test"
        artifactId = "test-arhive,test-archive2,test-archive3"
        buildSystem = PROVIDED
    }
}


"escrow-test-multi-module-project" {
    componentOwner = "user1"
    groupId = "org.octopusden.octopus.test.escrow.multi"
    artifactId = "parent,webapp,server"
    buildSystem = MAVEN
    vcsUrl = "ssh://hg@mercurial/test/escrow-test-multi-module-project"
    tag = '$version'
}
