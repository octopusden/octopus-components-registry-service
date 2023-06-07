import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.RepositoryType.CVS

bcomponent {
    componentOwner = "user1"
    groupId = "org.octopusden.octopus.bcomponent"
    artifactId = "test-cvs-maven-parent,test-cvs-maven-module1"
    vcsUrl = "back/build/test/sources/test-maven"
    repositoryType = CVS
    buildSystem = MAVEN
    tag = '$module-$cvsCompatibleVersion'
    buildFilePath = "test-cvs-maven-parent"
    teamcityReleaseConfigId = "testBt"
    jira {
        projectKey = "TEST"
        majorVersionFormat = '$major'
        releaseVersionFormat = '$major.$minor'
        buildVersionFormat = '$major.$minor.$build'
        lineVersionFormat = '$major'
        technical = true
    }
    build {
        javaVersion = "1.7"
        mavenVersion = "3.3"
        gradleVersion = "2.10"
        requiredProject = false
        systemProperties = "hello"
        projectVersion = "03.40.30"
        requiredTools = "BuildEnv,BuildLib"
        buildTasks = "assemble"
    }

    branch = "bcomponent-branch"

    deprecated = true


}



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
}
