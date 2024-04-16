import static org.octopusden.octopus.escrow.BuildSystem.BS2_0
import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.RepositoryType.CVS
import static org.octopusden.octopus.escrow.RepositoryType.MERCURIAL

final DEFAULT_TAG = '$module-$version'
final ANY_ARTIFACT = /[\w-\.]+/

Tools {
    BuildEnv {
        escrowEnvironmentVariable = "BUILD_ENV"
        targetLocation = "tools/BUILD_ENV"
        sourceLocation = "env.BUILD_ENV"
    }

    BuildLib {
        escrowEnvironmentVariable = "BUILD_LIB"
        targetLocation = "tools/BuildLib"
        sourceLocation = "env.BUILD_LIB"
    }

    AndroidSdk {
        escrowEnvironmentVariable = "AndroidSdk"
        targetLocation = "tools/AndroidSdk"
        sourceLocation = "env.AndroidSdk"
        installScript = "androidSdkInstaller"  //add .sh or .exe at installation
    }
}

Defaults {
    system = "NONE"
    releasesInDefaultBranch = true
    repositoryType = MERCURIAL
    buildSystem = MAVEN;
    tag = DEFAULT_TAG;
    artifactId = ANY_ARTIFACT
    jira {
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
        customer {
            versionFormat = '$versionPrefix-$baseVersionFormat'
        }
    }
}

"octopus-parent" {
    componentOwner = "user1"
    "[1.0,)" {
        vcsUrl = "ssh://hg@mercurial//maven-parent"
        buildSystem = MAVEN
        groupId = "org.octopusden.octopus"
        artifactId = "octopus-parent"
    }
    jira {
        projectKey = "MPARENT"
    }
}


"test-branch" {
    componentOwner = "user1"
    "[1.0,)" {
        vcsUrl = "ssh://hg@mercurial//maven-parent"
        buildSystem = MAVEN
        groupId = "org.octopusden.octopus"
        artifactId = "branches"
        branch = 'BRANCH-$major.$minor'
        tag = '$module-${version.replaceAll(\'\\\\.\', \'_\')}'
    }
    jira {
        projectKey = "TEST"
    }
}



bcomponent {
    componentOwner = "user1"
    build {
        javaVersion = "1.7"
        requiredTools = "BuildEnv"
    }
    "[1.12.1-150,)" {
        vcsUrl = "ssh://hg@mercurial/bcomponent"
        buildSystem = MAVEN
        repositoryType = MERCURIAL
        groupId = "org.octopusden.octopus.bcomponent"
        artifactId = ANY_ARTIFACT
        branch = 'BCOMPONENT-$major.$minor'
        build {
            requiredTools = "BuildEnv,AndroidSdk"
        }
    }

    "(,1.12.1-150)" {
        repositoryType = CVS;
        buildSystem = BS2_0;
        groupId = "org.octopusden.octopus.bcomponent"
        tag = 'BCOMPONENT-R-$cvsCompatibleVersion'
        branch = 'BCOMPONENT-$major.$minor'
    }
    jira {
        projectKey = "BCOMPONENT"
    }
}
