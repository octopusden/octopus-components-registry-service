import static BuildSystem.MAVEN
import static VCS.MERCURIAL

final DEFAULT_TAG = "$module-$version"
final ANY_ARTIFACT = /[\w-]+/

enum BuildSystem {
    BS2_0,
    MAVEN
}

enum VCS {
    CVS,
    MERCURIAL
}

Defaults {
    repositoryType = MERCURIAL
    buildSystem = MAVEN;
    versionRange = "(,)"
    tag = DEFAULT_TAG;
    invalidAttributeDefault = '123'
}

bcomponent {
    componentOwner = "user1"
    Mercurial {
        vcsUrl = "ssh://hg@mercurial/bcomponent"
        versionRange = "[1.12.1-150,)"
        buildSystem = MAVEN
        repositoryType = MERCURIAL
        groupId = "org.octopusden.octopus.bcomponent"
        artifactId = ANY_ARTIFACT
    }

    Cvs {
        vcsUrl = "OctopusSource/BuildSystem"
        versionRange = "(,1.12.1-150)"
        tag = '$module.$version'
        invalidAttributeCvs = 'zenit'
    }
    jira {
        projectKey = "BCOMPONENT"
    }
}

"jar-osgifier" {
    componentOwner = "user1"
    Mercurial {
        groupId = "org.octopusden.octopus.buildsystem"
        artifactId = "jar-osgifier "
        vcsUrl = "ssh://hg@mercurial//jar-osgifier"
    }
    jira {
        projectKey = "SYSTEM"
    }
}

"jar-osgifier" {
    componentOwner = "user1"
    Mercurial {
        groupId = "org.octopusden.octopus.buildsystem"
        artifactId = "jar-osgifier "
        vcsUrl = "ssh://hg@mercurial//jar-osgifier"
    }
}

"octopus-parent" {
    componentOwner = "user1"
    Mercurial {
        vcsUrl = "ssh://hg@mercurial//maven-parent"
        versionRange = "(,)"
        buildSystem = MAVEN
        repositoryType = MERCURIAL
        groupId = "org.octopusden.octopus"
        artifactId = "octopus-parent"
    }
}

"buildsystem-model" {
    componentOwner = "user1"
    Mercurial {
        groupId = "org.octopusden.octopus.buildsystem.model"
        artifactId = ANY_ARTIFACT
        vcsUrl = "ssh://hg@mercurial//buildsystem-model"
        versionRange = "(,)"
        buildSystem = MAVEN
        repositoryType = MERCURIAL
    }
}

"buildsystem-mojo" {
    componentOwner = "user1"
    Mercurial {
        groupId = "org.octopusden.octopus.mojo"
        artifactId = "buildsystem-maven-plugin"
        vcsUrl = "ssh://hg@mercurial/maven-buildsystem-plugin"
        versionRange = "(,)"
        buildSystem = MAVEN
        repositoryType = MERCURIAL
        tag = "maven-buildsystem-plugin-$version"
    }
}

"DBSchemeManager-client" {
    componentOwner = "user1"
    Mercurial {
        groupId = "org.octopusden.octopus.dbsm"
        artifactId = "component_client,component_client-library,dbsm-ant-task,dbsm-maven-plugin"
        vcsUrl = "ssh://hg@mercurial//DBSchemeManager-client"
        versionRange = "(,)"
        buildSystem = MAVEN
        repositoryType = MERCURIAL
        tag = "DBSchemeManager-client-$version"
    }
}

DBSchemeManager {
    componentOwner = "user1"
    Mercurial {
        groupId = "org.octopusden.octopus.dbsm"
        artifactId = "some-artifact"
        vcsUrl = "ssh://hg@mercurial//DBSchemeManager"
        versionRange = "(,)"
        buildSystem = MAVEN
        repositoryType = MERCURIAL
    }
}

"eclipse-product-exporter" {
    componentOwner = "user1"
    Mercurial {
        vcsUrl = "ssh://hg@mercurial//eclipse-product-exporter"
        artifactId = "eclipse-product-exporter"
        groupId = "org.octopusden.octopus.eclipse-product-exporter"
        buildSystem = MAVEN
        repositoryType = MERCURIAL
    }
}

screenshotter {
    componentOwner = "user1"
    Mercurial {
        groupId = "org.octopusden.octopus.util"  //todo!
        artifactId = "screenshotter"
        vcsUrl = "ssh://hg@mercurial//screenshotter"
        buildSystem = MAVEN
        repositoryType = MERCURIAL
        tag = "screenshotter-1.65"
    }
}

"eclipse-core" {
    componentOwner = "user1"
    Mercurial {
        vcsUrl = "ssh://hg@mercurial/bcomponent-binary-projectDependencies/eclipse"
        artifactId = "eclipse-core"
        groupId = "org.octopusden.octopus.eclipse"
        buildSystem = MAVEN            //todo
        repositoryType = MERCURIAL
    }
}

saxon {
    componentOwner = "user1"
    Mercurial {
        vcsUrl = "ssh://hg@mercurial/bcomponent-binary-projectDependencies/saxon"
        artifactId = ANY_ARTIFACT
        groupId = "org.octopusden.octopus.net.sourceforge.saxon"
        buildSystem = MAVEN            //todo
        repositoryType = MERCURIAL
    }
}

ant {
    componentOwner = "user1"
    Mercurial {
        vcsUrl = "ssh://hg@mercurial/bcomponent-binary-projectDependencies/ant"
        artifactId = ANY_ARTIFACT
        groupId = "org.octopusden.octopus.org.apache.ant"
        buildSystem = MAVEN            //todo
        repositoryType = MERCURIAL
    }
}

//todo: validate for duplicates!
xmlbeans {
    componentOwner = "user1"
    Mercurial {
        vcsUrl = "ssh://hg@mercurial/bcomponent-binary-projectDependencies/xmlbeans"
        artifactId = "xmlbeans(9"
        groupId = "org.octopusden.octopus.org.apache.xmlbeans"
        buildSystem = MAVEN            //todo
        repositoryType = MERCURIAL
    }
}
