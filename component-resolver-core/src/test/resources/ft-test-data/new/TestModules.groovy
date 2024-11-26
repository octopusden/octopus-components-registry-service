import org.octopusden.octopus.escrow.BuildSystem

import static org.octopusden.octopus.escrow.BuildSystem.*
import static org.octopusden.octopus.escrow.RepositoryType.*

"test-git-component" {
    componentOwner = "user1"
    groupId = "org.octopusden.octopus.test"
    artifactId = "test-git-component"

    vcsSettings {
        repositoryType = GIT
        vcsUrl = "git@gitlab:rdpa/sandbox/test-component-maven.git"
        branch = "master"
    }

    "(,2),(2,)" {
    }
    "[2]" {
        vcsSettings {
            branch = "BRANCH"
            tag = "BRANCH"
        }
    }
    jira {
        projectKey = "TEST"
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
        component { versionPrefix = "test-git-component" }
    }
}

"test-maven-release" {
    componentOwner = "user1"
    "${ALL_VERSIONS}" {
        groupId = "org.octopusden.octopus.test"
        artifactId = "maven-dependencyconflict-plugin"
        buildSystem = PROVIDED
        jiraProjectKey = "MEOW"
        jiraMajorVersionFormat = '$major.$minor'
        jiraReleaseVersionFormat = '$major.$minor.$service'
    }
    jira {
        projectKey = "TEST"
    }
}

test {
    componentOwner = "user1"
    "${ALL_VERSIONS}" {
        buildSystem = BuildSystem.BS2_0;
        tag = 'TEST-ESCROW-R-$cvsCompatibleVersion'
    }
    jira {
        projectKey = "TEST"
    }
}

"test-project" {
    componentOwner = "aarshavin"
    releaseManager = "aarshavin"
    securityChampion = "aarshavin"
    componentDisplayName = "Test Project"
    deprecated = true
    vcsUrl = "ssh://hg@mercurial/test-project"
    tag = "jdbc-branch"
    "[10,12)" {
        groupId = "org.octopusden.octopus.test"
        artifactId = "test-project"
        build {
            javaVersion = "1.7"
            requiredProject = true
            projectVersion = "03.49"
            requiredTools = "BuildEnv"
        }

        distribution {
            explicit = true
            external = true
        }
    }
    jira {
        projectKey = "TEST"
        component { versionPrefix = "test-project" }
    }

}

"provided-test-dependency" {
    componentOwner = "user1"
    artifactId = "commoncomponent"
    buildSystem = PROVIDED
    "${ALL_VERSIONS}" {
        groupId = "org.octopusden.octopus.test"
    }
    jira {
        projectKey = "TEST"
        component { versionPrefix = "provided-test-dependency" }
    }
}

"provided-test-archive" {
    componentOwner = "user1"
    "[1.0],[2.0],[3.0]" {
        groupId = "org.octopusden.octopus.escrow.test"
        artifactId = "test-arhive,test-archive2,test-archive3"
        buildSystem = PROVIDED
    }
    jira {
        projectKey = "TEST"
        component { versionPrefix = "provided-test-archive" }
    }
}

"provided-component-with-dependencies" {
    componentOwner = "user1"
    "[1.0]" {
        groupId = "org.octopusden.octopus.escrow.test"
        artifactId = "test-artifact-with-dependencies"
        buildSystem = PROVIDED
    }
    jira {
        projectKey = "TEST"
        component { versionPrefix = "provided-component-with-dependencies" }
    }
}

"test-cvs-maven" {
    componentOwner = "user1"
    deprecated = true
    groupId = "org.octopusden.octopus.test"
    artifactId = "test-cvs-maven-parent,test-cvs-maven-module1"
    vcsUrl = "back/build/test/sources/test-maven"
    repositoryType = CVS
    tag = '$module-$cvsCompatibleUnderscoreVersion'
    "[3.1]" {
        buildFilePath = "test-cvs-maven-parent"
    }
    "[3.0]" {

    }
    jira {
        projectKey = "TEST"
        component { versionPrefix = "test-cvs-maven" }
    }
}

"escrow-test-project" {
    componentOwner = "user1"
    deprecated = true
    groupId = "org.octopusden.octopus.test"
    artifactId = "escrow-test-project"
    buildSystem = MAVEN
    vcsSettings {
        vcsUrl = "ssh://hg@mercurial/test/escrow-test-project"
    }
    "(,3),(3,)" {

    }
    "[3]" {
        artifactId = "escrow-test-project,module1,module2"
        branch = "BRANCH"
        tag = "BRANCH"
        build {
            javaVersion = "1.8"
        }
    }
    build {
        mavenVersion = "LATEST"
    }
    jira {
        projectKey = "TEST"
        component { versionPrefix = "escrow-test-project" }
    }
}

// BRANCH
"test-bom1" {
    componentOwner = "user1"
    groupId = "org.octopusden.octopus.test"
    artifactId = "bom1"

    vcsSettings {
        repositoryType = GIT
        vcsUrl = "git@gitlab:gr/escrow/test-bom1.git"
        branch = "master"
    }
    jira {
        projectKey = "TEST"
        component { versionPrefix = "test-bom1" }
    }
}

// BRANCH
"test-bom2" {
    componentOwner = "user1"
    groupId = "org.octopusden.octopus.test"
    artifactId = "bom2"

    vcsSettings {
        repositoryType = GIT
        vcsUrl = "git@gitlab:gr/escrow/test-bom2.git"
        branch = "master"
    }
    jira {
        projectKey = "TEST"
        component { versionPrefix = "test-bom2" }
    }
}

"escrow-test-multi-module-project" {
    componentOwner = "user1"
    groupId = "org.octopusden.octopus.test.escrow.multi"
    artifactId = "parent,webapp,server"
    buildSystem = MAVEN
    vcsUrl = "ssh://hg@mercurial/test/escrow-test-multi-module-project"
    tag = '$version'
    jira {
        projectKey = "TEST"
        component { versionPrefix = "escrow-test-multi-module-project" }
    }
}

"test-npm-maven" {
    componentOwner = "user1"
    groupId = "org.octopusden.octopus.escrow.test"
    artifactId = "test-npm-maven"

    vcsSettings {
        repositoryType = GIT
        vcsUrl = "git@gitlab:gr/escrow/test-npm.git"
    }
    buildSystem = MAVEN

    build {
        requiredTools = "BuildEnv"
        mavenVersion = "3.3.9"
    }
    jira {
        projectKey = "TEST"
        component { versionPrefix = "test-npm-maven" }
    }
}
