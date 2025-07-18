import static org.octopusden.octopus.escrow.BuildSystem.*
import static org.octopusden.octopus.escrow.RepositoryType.*

Defaults {
    system = "NONE"
    tag = '$module-$version'
    releasesInDefaultBranch = true
    solution = false
    distribution {
        securityGroups {
            read = "vfiler1-default#group"
        }
    }
    system = "NONE"
}

"test-release" {
    componentOwner = "user9"
    "$ALL_VERSIONS" {
        groupId = "org.octopusden.octopus.test"
        artifactId = "test-release"
        jira {
            projectKey = "MSP"
            majorVersionFormat = '$major.$minor'
            releaseVersionFormat = '$major.$minor.$service'
            displayName = "MULTI SUPER PROJJ"
        }
    }
}


"sub-component1" {
    componentOwner = "user9"
    "$ALL_VERSIONS" {
        groupId = "org.octopusden.octopus.sub1"
        artifactId = "sub-component1"
        jira {
            projectKey = "FIRSTSUB"
            majorVersionFormat = '$major.$minor'
            releaseVersionFormat = '$major.$minor.$service'
            displayName = "FIRST SUB SOMPONRNT"
        }
    }
}

"sub-component2" {
    componentOwner = "user9"
    "$ALL_VERSIONS" {
        groupId = "org.octopusden.octopus.sub2"
        artifactId = "sub-component2"
        jira {
            projectKey = "SECONDSUB"
            majorVersionFormat = '$major'
            releaseVersionFormat = '$major.$minor'
            displayName = "SECOND SUB COMPONENT"
        }
    }
}

"test-project" {
    componentOwner = "user9"
    groupId = "org.octopusden.octopus.test"
    artifactId = "test-project"
    jira {
        projectKey = "TEST"
        displayName = "TEST PROJECT"
    }
    build {
        javaVersion = "1.7"
    }
}

"SUB_WITH_SIMPLE_VERSION_FORMAT" {
    componentOwner = "user9"
    groupId = "org.octopusden.octopus.system-simple-format"
    artifactId = "commoncomponent-test-simple-format"
    versionRange = "[1,)"
    jira {
        projectKey = "SUB"
        majorVersionFormat = '$major'
        releaseVersionFormat = '$major.$minor'
        buildVersionFormat = '$major.$minor'
        hotfixVersionFormat = '$major.$minor.$service'
        displayName = "Subcomponent with simple version format"
        component {
            versionPrefix = 's1mple'
            versionFormat = '$versionPrefix.$baseVersionFormat'
        }
    }

    repositoryType = MERCURIAL
    vcsUrl = "ssh://hg@mercurial/sub"
    branch = "v2"

}

"SUB" {
    componentOwner = "user9"
    groupId = "org.octopusden.octopus.system-test2"
    artifactId = "commoncomponent-test2"
    versionRange = "[1,)"
    jira {
        projectKey = "SUB"
        majorVersionFormat = '$major'
        releaseVersionFormat = '$major.$minor'
        buildVersionFormat = '$major.$minor.$service'
        hotfixVersionFormat = '$major.$minor.$service-$build'
        displayName = "PPROJECT WITH CLIENT COMPONENT"
        customer {
            versionPrefix = "sub1k"
        }
    }

    repositoryType = MERCURIAL
    vcsUrl = "ssh://hg@mercurial/sub"
    branch = "v2"


    components {
        "client" {
            componentDisplayName = "CLIENT WB"
            componentOwner = "user"
            releaseManager = "user"
            securityChampion = "user"
            jira {

                customer {
                    versionPrefix = "hlk"
                }
            }

            distribution {
                explicit = true
                external = true
            }

            vcsSettings {
                repositoryType = MERCURIAL
                vcsUrl = "ssh://hg@mercurial/client"
            }
            artifactId = "commoncomponent-test-client"
        }
    }

}

"commoncomponent-test" {
    componentOwner = "user9"
    buildSystem = PROVIDED
    groupId = "org.octopusden.octopus.system-test"
    artifactId = "commoncomponent-test,monitoring-distribution,monitoring-gates,monitoring-jdbc-gate"
    versionRange = "[1,)"
    jira {
        projectKey = "SUB"
        majorVersionFormat = '$major'
        releaseVersionFormat = '$major.$minor'
        displayName = "commoncomponent TEST"
    }
}

"TEST-VERSION" {
    componentOwner = "user9"
    groupId = "org.octopusden.octopus.testver"
    artifactId = "testver"
    "[999,)" {}
    buildSystem = MAVEN
    repositoryType = MERCURIAL
    vcsUrl = "ssh://hg@mercurial/test-component"
}

"TESTONE" {
    componentDisplayName = "Test ONE display name"
    componentOwner = "adzuba"
    releaseManager = "user"
    securityChampion = "user"
    groupId = "org.octopusden.octopus.test"
    artifactId = "test2"
    componentDisplayName = "Test ONE display name"
    system = "CLASSIC,ALFA"
    clientCode = "CLIENT_CODE"
    releasesInDefaultBranch = false
    solution = true
    jira {
        projectKey = "TESTONE"
        majorVersionFormat = '$major'
        releaseVersionFormat = '$major.$minor'
        buildVersionFormat = '$major.$minor.$service'
        hotfixVersionFormat = '$major.$minor.$service.$fix'
        displayName = "TESTONE DISPLAY NAME WITH VERSIONS-API"
    }

    build {
        requiredTools = "BuildEnv,PowerBuilderCompiler170"
        javaVersion = "11"
        mavenVersion = "3.6.3"
        gradleVersion = "LATEST"
        buildTasks = "clean build"
    }

    repositoryType = MERCURIAL
    vcsUrl = "ssh://hg@mercurial/test-component"
    branch = "v2"

    components {
        "versions-api" {
            componentOwner = "user9"
            parentComponent = "TESTONE"
            artifactId = "versions-api"
            jira {
                majorVersionFormat = '$major'
                releaseVersionFormat = '$major.$minor'
                displayName = "VERSIONS API COMPONENT"
                component {
                    versionPrefix = "versions-api"
                    versionFormat = '$versionPrefix.$baseVersionFormat'
                    hotfixVersionFormat = '$releaseVersion-$hotfixSuffix'
                }
            }
            vcsUrl = "ssh://hg@mercurial/versions-api"
            branch = "default"
        }
    }

    distribution {
        explicit = false
        external = false
        GAV = "org.octopusden.octopus.test:versions-api:jar"
        docker = "test/versions-api"
    }

    buildFilePath = "build"
}


TEST_COMPONENT {
    componentOwner = "user9"
    groupId = "org.octopusden.octopus.test2"
    artifactId = "test2"
    jira {
        projectKey = "PROJ"
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
        lineVersionFormat = '$major.$minor'
        buildVersionFormat = '$major.$minor.$service-$fix'
    }
    buildSystem = MAVEN
    repositoryType = MERCURIAL
    vcsUrl = "ssh://hg@mercurial/test-component"
    branch = "v2"
}


TEST_COMPONENT_WITHOUT_VCS {
    componentOwner = "user9"
    "$ALL_VERSIONS" {
        groupId = "org.octopusden.octopus.test2-vcs"
        artifactId = "test2-vcs"
        jira {
            projectKey = "PROJ"
            majorVersionFormat = '$major.$minor'
            releaseVersionFormat = '$major.$minor.$service'
            buildVersionFormat = '$major.$minor.$service-$fix'
            customer {
                versionPrefix = 'test'
                versionFormat = '$versionPrefix.$baseVersionFormat'
            }
        }
    }
}


"MYPRJ" {
    componentOwner = "user9"
    "$ALL_VERSIONS" {
        groupId = "org.octopusden.octopus.myproj"
        artifactId = "test"
        jira {
            projectKey = "MYPRJ"
            majorVersionFormat = '$major.$minor'
            releaseVersionFormat = '$major.$minor.$service'
        }
    }
}

"FAKE" {
    componentOwner = "user9"
    "$ALL_VERSIONS" {
        groupId = "org.octopusden.octopus.fake"
        artifactId = "test"
        jira {
            projectKey = "FAKE"
            majorVersionFormat = '$major.$minor'
            releaseVersionFormat = '$major.$minor.$service'
        }
    }
}

"test-simple" {
    componentOwner = "user9"
    "$ALL_VERSIONS" {
        groupId = "org.octopusden.octopus.test-simple"
        artifactId = "test-simple"
        versionRange = "[1.2,)"
    }
    jira {
        projectKey = "TEST"
    }
}

TEST_COMPONENT2 {
    componentDisplayName = "TEST_COMPONENT2"
    componentOwner = "aarshavin"
    releaseManager = "aarshavin"
    securityChampion = "aarshavin"
    system = "CLASSIC"

    groupId = "org.octopusden.octopus.conponentt"
    artifactId = "conponentt"
    jira {
        projectKey = "TEST_COMPONENT2"
        majorVersionFormat = '$major02.$minorC.$serviceC'
        releaseVersionFormat = '$major02.$minor02.$service02.$fix02'
        buildVersionFormat = '$major02.$minor02.$service02.$fix02-$build'
        hotfixVersionFormat = '$major02.$minor02.$service02.$fix02-$build'
    }
    distribution {
        explicit = true
        external = true
    }
    repositoryType = MERCURIAL
    vcsUrl = "ssh://hg@mercurial/test-component"
    branch = "v2"

}


"TEST_COMPONENT2_NOT_EXPLICIT" {
    componentOwner = "user9"
    groupId = "org.octopusden.octopus.cardnotexpl"
    artifactId = "componentnoteplx"
    system = "CLASSIC"
    jira {
        projectKey = "TEST_COMPONENT2"
        majorVersionFormat = '$major02.$minorC.$serviceC'
        releaseVersionFormat = '$major02.$minor02.$service02.$fix02'
        buildVersionFormat = '$major02.$minor02.$service02.$fix02-$build'
        hotfixVersionFormat = '$major02.$minor02.$service02.$fix02-$build'
        component {
            versionPrefix = 'not-expl'
        }
    }
    distribution {
        explicit = false
        external = true
    }
    repositoryType = MERCURIAL
    vcsUrl = "ssh://hg@mercurial/test-component"
    branch = "v2"

}


"TEST_COMPONENT2_WITH_UPDATED_FORMAT" {
    componentOwner = "user9"
    groupId = "org.octopusden.octopus.conponentt_updated"
    artifactId = "conponentt_updated"
    repositoryType = MERCURIAL
    vcsUrl = "ssh://hg@mercurial/releng"
    branch = "v2"
    system = "CLASSIC"
    jira {
        projectKey = "TEST_COMPONENT2"
        majorVersionFormat = '$major.$minor'
        customer {
            versionPrefix = 'format'
        }
    }

    "(,03.38.29]" {
        jira {
            releaseVersionFormat = '$major.$minor.$service'
        }
    }

    "(03.38.29,)" {
        jira {
            releaseVersionFormat = '$major.$minor.$service-$fix'
        }
    }
}


"TEST_COMPONENT2_WITH_EXTERNAL_REGISTRY" {
    componentDisplayName = "TEST_COMPONENT2_WITH_EXTERNAL_REGISTRY"
    componentOwner = "user8"
    releaseManager = "user8"
    securityChampion = "user8"
    system = "CLASSIC"
    groupId = "org.octopusden.octopus.conponentt"
    artifactId = "external"
    jira {
        projectKey = "TEST_COMPONENT2"
        lineVersionFormat = '$major02.$minorC'
        majorVersionFormat = '$major02.$minorC.$serviceC'
        releaseVersionFormat = '$major02.$minor02.$service02.$fix02'
        buildVersionFormat = '$major02.$minor02.$service02.$fix02-$build04'
        hotfixVersionFormat = '$major02.$minor02.$service02.$fix02-$build04'
        customer {
            versionPrefix = 'external-roots'
            versionFormat = '$versionPrefix.$baseVersionFormat'
        }
    }
    distribution {
        explicit = true
        external = true
    }
    vcsSettings {
        externalRegistry = "componentc_db"
    }
}

"SUB_COMPONENT_TWO" {
    componentOwner = "user9"
    groupId = "org.octopusden.octopus.sub_component_two"
    artifactId = "sub_component_two"
    repositoryType = MERCURIAL
    buildSystem = MAVEN
    vcsUrl = "ssh://hg@mercurial/technical"
    branch = "v2"
    jira {
        projectKey = "TEST_COMPONENT2"
        technical = true
        displayName = "SUB COMPONENT TWO"
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
        buildVersionFormat = '$major.$minor.$service-$fix'
        component {
            versionPrefix = 'sub_component_two'
        }
    }
}

"SUB_COMPONENT_ONE" {
    componentOwner = "user9"
    groupId = "org.octopusden.octopus.sub_component_one"
    artifactId = "sub_component_one"
    repositoryType = MERCURIAL
    vcsUrl = "ssh://hg@mercurial/technical"
    branch = "v2"
    jira {
        projectKey = "TEST_COMPONENT2"
        technical = true
        displayName = "SUB COMPONENT ONE"
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
        buildVersionFormat = '$major.$minor.$service-$fix'
        component {
            versionPrefix = 'sub_component_one'
        }
    }
}

"TECHNICAL_COMPONENT" {
    componentOwner = "user9"
    groupId = "org.octopusden.octopus.conponentt_technical"
    artifactId = "conponentt_technical"
    repositoryType = MERCURIAL
    vcsUrl = "ssh://hg@mercurial/technical"
    branch = "v2"
    jira {
        projectKey = "TEST_COMPONENT2"
        lineVersionFormat = '$major02.$minorC'
        majorVersionFormat = '$major02.$minorC.$serviceC'
        releaseVersionFormat = '$major02.$minor02.$service02.$fix02'
        buildVersionFormat = '$major02.$minor02.$service02.$fix02-$build'
        hotfixVersionFormat = '$major02.$minor02.$service02.$fix02-$build'
        technical = true
        displayName = "TECHNICAL COMPONENT"
        component {
            versionPrefix = 'technical'
        }
    }
}

"DOUBLE_TECHNICAL_COMPONENT" {
    componentOwner = "user9"
    groupId = "org.octopusden.octopus.conponentt_dtechnical"
    artifactId = "conponentt_double_technical"
    repositoryType = MERCURIAL
    vcsUrl = "ssh://hg@mercurial/dtechnical"
    branch = "v2"
    jira {
        projectKey = "TEST_COMPONENT2"
        lineVersionFormat = '$major02.$minorC'
        majorVersionFormat = '$major02.$minorC.$serviceC'
        releaseVersionFormat = '$major02.$minor02.$service02.$fix02'
        buildVersionFormat = '$major02.$minor02.$service02.$fix02-$build'
        hotfixVersionFormat = '$major02.$minor02.$service02.$fix02-$build'
        technical = true
        displayName = "DOUBLE TECHNICAL COMPONENT"
        component {
            versionPrefix = 'doubletechnical'
        }
    }
}

"TEST_COMPONENT2_WITH_EXTERNAL_REGISTRY_NOT_EXPLICIT" {
    componentOwner = "user9"
    groupId = "org.octopusden.octopus.componentexternalregistrynotexplicit"
    artifactId = "externnotexist"
    jira {
        projectKey = "TEST_COMPONENT2"
        lineVersionFormat = '$major02.$minorC'
        majorVersionFormat = '$major02.$minorC.$serviceC'
        releaseVersionFormat = '$major02.$minor02.$service02.$fix02'
        buildVersionFormat = '$major02.$minor02.$service02.$fix02-$build04'
        hotfixVersionFormat = '$major02.$minor02.$service02.$fix02-$build04'
        customer {
            versionPrefix = 'external-roots-not-explicit'
            versionFormat = '$versionPrefix.$baseVersionFormat'
        }
    }
    distribution {
        explicit = false
        external = true
    }
    vcsSettings {
        externalRegistry = "componentc_db"
    }
}

"TECHNICAL_TEST_COMPONENT2_WITH_EXTERNAL_REGISTRY" {
    componentOwner = "user9"
    groupId = "org.octopusden.octopus.conponenttechnicalexternalregistry"
    jira {
        projectKey = "TEST_COMPONENT2"
        technical = true
        lineVersionFormat = '$major02.$minorC'
        majorVersionFormat = '$major02.$minorC.$serviceC'
        releaseVersionFormat = '$major02.$minor02.$service02.$fix02'
        buildVersionFormat = '$major02.$minor02.$service02.$fix02-$build04'
        hotfixVersionFormat = '$major02.$minor02.$service02.$fix02-$build04'
        displayName = "DB"
        customer {
            versionPrefix = 'db'
            versionFormat = '$baseVersionFormat-$versionPrefix'
        }
    }
    distribution {
        explicit = false
        external = true
    }
    vcsSettings {
        externalRegistry = "componentc_db"
    }
}

"TEST_COMPONENT2_WITH_EXTERNAL_REGISTRY_NOT_EXISTS" {
    componentDisplayName = "TEST_COMPONENT2_WITH_EXTERNAL_REGISTRY_NOT_EXISTS"
    componentOwner = "user9"
    releaseManager = "user9"
    securityChampion = "user9"
    groupId = "org.octopusden.octopus.cardnotexis"
    artifactId = "externnotexist"
    "${ALL_VERSIONS}" {
        jira {
            projectKey = "TEST_COMPONENT2"
            majorVersionFormat = '$major02.$minorC.$serviceC'
            releaseVersionFormat = '$major02.$minor02.$service02.$fix02'
            buildVersionFormat = '$major02.$minor02.$service02.$fix02-$build04'
            hotfixVersionFormat = '$major02.$minor02.$service02.$fix02-$build04'
            customer {
                versionPrefix = 'external-roots-not'
                versionFormat = '$versionPrefix.$baseVersionFormat'
            }
        }
        distribution {
            explicit = true
            external = true
        }
        vcsSettings {
            externalRegistry = "component_db_not_exists"
        }
    }
}

"TEST_COMPONENT5" {
    componentOwner = "user9"
    groupId = "org.octopusden.octopus.cardnotexis"
    artifactId = "test_component5"
    "${ALL_VERSIONS}" {
        jira {
            projectKey = "TEST_COMPONENT2"
            majorVersionFormat = '$major02.$minorC.$serviceC'
            releaseVersionFormat = '$major02.$minor02.$service02.$fix02'
            buildVersionFormat = '$major02.$minor02.$service02.$fix02-$build04'
            hotfixVersionFormat = '$major02.$minor02.$service02.$fix02-$build04'
            customer {
                versionPrefix = 'test_component5'
                versionFormat = '$versionPrefix.$baseVersionFormat'
            }
        }
        distribution {
            explicit = false
            external = true
        }
        vcsSettings {
            externalRegistry = "NOT_AVAILABLE"
        }
    }
}


"TEST_COMPONENT2_INTERNAL" {
    componentOwner = "user9"
    groupId = "org.octopusden.octopus.conponentt_intern"
    artifactId = "conponentt"
    "${ALL_VERSIONS}" {
        jira {
            projectKey = "TEST_COMPONENT2"
            majorVersionFormat = '$major02.$minorC.$serviceC'
            releaseVersionFormat = '$major02.$minor02.$service02.$fix02'
            buildVersionFormat = '$major02.$minor02.$service02.$fix02-$build'
            hotfixVersionFormat = '$major02.$minor02.$service02.$fix02-$build'
            component {
                versionPrefix = 'internal'
            }
        }
        distribution {
            explicit = false
            external = false
        }
        repositoryType = MERCURIAL
        vcsUrl = "ssh://hg@mercurial/test-component"
        branch = "v2"

    }
}


"TEST_COMPONENT2_WITH_CVS_ROOT" {
    componentOwner = "user9"
    groupId = "org.octopusden.octopus.conponentt_cvs"
    artifactId = "conponentt_cvs"
    repositoryType = CVS
    vcsUrl = "OctopusSource/COMPONENT"
    branch = 'MAIN'
    jira {
        projectKey = "TEST_COMPONENT2"
        majorVersionFormat = '$major02.$minorC.$serviceC'
        releaseVersionFormat = '$major02.$minor02.$service02.$fix02'
        buildVersionFormat = '$major02.$minor02.$service02.$fix02-$build'
        hotfixVersionFormat = '$major02.$minor02.$service02.$fix02-$build'
        customer {
            versionPrefix = 'cvs'
        }
    }
}


"TEST_COMPONENT2_WITH_FORMATTED_CVS_ROOT" {
    componentOwner = "user9"
    groupId = "org.octopusden.octopus.conponentt_fcvs"
    artifactId = "conponentt_fcvs"
    repositoryType = CVS
    vcsUrl = "OctopusSource/COMPONENT"
    branch = 'TEST_COMPONENT2_${major02}_${minor02}_${service02}'
    jira {
        projectKey = "TEST_COMPONENT2"
        majorVersionFormat = '$major02.$minorC.$serviceC'
        releaseVersionFormat = '$major02.$minor02.$service02.$fix02'
        buildVersionFormat = '$major02.$minor02.$service02.$fix02-$build'
        hotfixVersionFormat = '$major02.$minor02.$service02.$fix02-$build'
        customer {
            versionPrefix = 'formatted'
        }
    }
}


"TEST_COMPONENT2_WITH_MERCURIAL_ROOT" {
    componentOwner = "user9"
    groupId = "org.octopusden.octopus.conponentt_merc"
    artifactId = "conponentt_merc"
    repositoryType = MERCURIAL
    vcsUrl = "ssh://hg@mercurial/RELENG"
    branch = "v2"
    jira {
        projectKey = "TEST_COMPONENT2"
        lineVersionFormat = '$major02.$minorC'
        majorVersionFormat = '$major02.$minorC.$serviceC'
        releaseVersionFormat = '$major02.$minor02.$service02.$fix02'
        buildVersionFormat = '$major02.$minor02.$service02.$fix02-$build'
        hotfixVersionFormat = '$major02.$minor02.$service02.$fix02-$build'
        customer {
            versionPrefix = 'mercurial'
        }
    }
}

"TEST_COMPONENT2_WITH_GIT_ROOT" {
    componentOwner = "user9"
    groupId = "org.octopusden.octopus.conponentt_git"
    artifactId = "conponentt_git"
    repositoryType = GIT
    vcsUrl = "ssh://git@gitlab:RELENG.git"
    branch = "v2"
    jira {
        projectKey = "TEST_COMPONENT2"
        lineVersionFormat = '$major02.$minorC'
        majorVersionFormat = '$major02.$minorC.$serviceC'
        releaseVersionFormat = '$major02.$minor02.$service02.$fix02'
        buildVersionFormat = '$major02.$minor02.$service02.$fix02-$build'
        hotfixVersionFormat = '$major02.$minor02.$service02.$fix02-$build'
        customer {
            versionPrefix = 'git'
        }
    }
}

"TEST_COMPONENT2_WITH_SEVERAL_BRANCHES" {
    componentOwner = "user9"
    groupId = "org.octopusden.octopus.conponentt_sevbranches"
    artifactId = "conponentt_sevbranches"
    jira {
        projectKey = "TEST_COMPONENT2"
        lineVersionFormat = '$major02.$minorC'
        majorVersionFormat = '$major02.$minorC.$serviceC'
        releaseVersionFormat = '$major02.$minor02.$service02.$fix02'
        buildVersionFormat = '$major02.$minor02.$service02.$fix02-$build'
        hotfixVersionFormat = '$major02.$minor02.$service02.$fix02-$build'
        customer {
            versionPrefix = 'several-branches'
        }
    }

    "(,03.38.25]" {
        vcsSettings {
            vcsUrl = "ssh://hg@mercurial/releng"
            repositoryType = MERCURIAL
            branch = "v2"
        }
    }

    "(03.38.25,03.38.31]" {
        vcsSettings {
            vcsUrl = "ssh://hg@mercurial/releng"
            repositoryType = MERCURIAL
            branch = "v2"
        }
    }

    "(03.38.31,)" {
        vcsSettings {
            vcsUrl = "ssh://hg@mercurial/releng"
            repositoryType = MERCURIAL
            branch = "v3"
        }
    }
}

"TEST_COMPONENT2_WITH_SEVERAL_ROOTS" {
    componentOwner = "user9"
    groupId = "org.octopusden.octopus.conponentt_sev"
    artifactId = "conponentt_sev"
    vcsSettings {
        cvs {
            repositoryType = CVS
            vcsUrl = "OctopusSource/COMPONENT"
            branch = 'MAIN'
        }

        mercurial {
            repositoryType = MERCURIAL
            vcsUrl = "ssh://hg@mercurial/releng"
            branch = "v2"
        }
    }
    jira {
        projectKey = "TEST_COMPONENT2"
        majorVersionFormat = '$major02.$minorC.$serviceC'
        releaseVersionFormat = '$major02.$minor02.$service02.$fix02'
        buildVersionFormat = '$major02.$minor02.$service02.$fix02-$build'
        hotfixVersionFormat = '$major02.$minor02.$service02.$fix02-$build'
        customer {
            versionPrefix = 'several'
        }
    }

}


TEST_COMPONENT4 {
    componentOwner = "user9"
    groupId = "org.octopusden.octopus.ddd"
    jira {
        projectKey = "TEST_COMPONENT4"
        majorVersionFormat = '$major02.$minorC.$serviceC'
        releaseVersionFormat = '$major02.$minor02.$service02.$fix02'
    }

    vcsSettings {
        repositoryType = MERCURIAL
        vcsUrl = "ssh://hg@mercurial/test-ptddd-component"
        branch = "v2"
    }
    distribution {
        explicit = false
        external = true
    }
}

"TEST_COMPONENT4_TECHNICAL_COMPONENT" {
    componentOwner = "user9"
    groupId = "org.octopusden.octopus.ddd_technical"
    vcsSettings {
        repositoryType = MERCURIAL
        vcsUrl = "ssh://hg@mercurial/ddd/technical"
        branch = "v2"
        tag = "HELLO TAG23434"
    }
    jira {
        projectKey = "TEST_COMPONENT4"
        lineVersionFormat = '$major02.$minorC'
        majorVersionFormat = '$major02.$minorC.$serviceC'
        releaseVersionFormat = '$major02.$minor02.$service02.$fix02'
        buildVersionFormat = '$major02.$minor02.$service02.$fix02-$build'
        hotfixVersionFormat = '$major02.$minor02.$service02.$fix02-$build'
        technical = true
        displayName = "TECHNICAL COMPONENT"
        component {
            versionPrefix = 'technical'
        }
    }
}

"TEST_COMPONENT3" {
    componentDisplayName = "3-D Secure"
    componentOwner = "user4"
    releaseManager = "user4"
    securityChampion = "user4"
    vcsUrl = 'ssh://hg@mercurial/tdsecure'
    groupId = "org.octopusden.octopus.test"
    tag = 'octopustds-$version'
    jira {
        projectKey = "TDS"
    }
    build {
        javaVersion = "1.8"
    }
    distribution {
        GAV = "org.octopusden.octopus.test:octopusmpi:war," +
            "org.octopusden.octopus.test:octopusacs:war," +
            "org.octopusden.octopus.test:demo:war" +
            ',file:///acs:$major-$minor-$service-$fix'
        explicit = true
        external = true
        securityGroups {
            read = "vfiler1#group"
        }
    }

    "(,1.0.107)" {

    }

    "[1.0.107,)" {
        jira {
            releaseVersionFormat = '$major.$minor.$service-$fix'
        }
        tag = 'tdsecure-$version'
    }
}

"TEST_COMPONENT_WITH_GOLANG_BUILD_SYSTEM" {
    componentOwner = "user9"
    groupId = "org.octopusden.octopus.test.golang"
    artifactId = "test-golang"
    buildSystem = GOLANG
}

"TEST_COMPONENT_WITH_IN_CONTAINER_BUILD_SYSTEM" {
    componentOwner = "user9"
    groupId = "org.octopusden.octopus.test"
    artifactId = "in-container"
    buildSystem = IN_CONTAINER
}

"TEST_COMPONENT_BUILD_TOOLS" {
    componentOwner = "user9"
    groupId = "org.octopusden.octopus.test.build.tools"
    artifactId = "test-build-tools"
    build { }
}

"TEST_PT_K_DB" {
    componentOwner = "user9"
    groupId = "org.octopusden.octopus.test"
    artifactId = "pt-k-db"
}


"TEST_COMPONENT_WITH_DOCKER_1" {
    componentOwner = "user9"
    groupId = "org.octopusden.octopus.test.docker"
    jira {
        projectKey = "TEST_DOCKER"
    }
    distribution {
        explicit = false
        external = true
        docker = 'test-docker-1'
    }
}

"TEST_COMPONENT_WITH_DOCKER_1_1" {
    componentOwner = "user9"
    groupId = "org.octopusden.octopus.test.docker"
    jira {
        projectKey = "TEST_DOCKER"
    }
    distribution {
        explicit = false
        external = true
        docker = 'test-docker-1_1'
    }
}

"TEST_COMPONENT_WITH_DOCKER_1_2" {
    componentOwner = "user9"
    groupId = "org.octopusden.octopus.test.docker"
    jira {
        projectKey = "TEST-D"
    }
    distribution {
        explicit = false
        external = true
        docker = 'test-docker-1_2:jdk11'
    }
}

"TEST_COMPONENT_WITH_DOCKER_2" {
    componentOwner = "user9"
    groupId = "org.octopusden.octopus.test.docker"

    jira {
        projectKey = "TEST_DOCKER"
    }

    distribution {
        explicit = false
        external = true
    }

    "(,1.0.0]" {
        distribution {
            docker = 'test-docker-first'
        }
    }

    "(1.0.0, 2.0.0)" {
        distribution {
            docker = 'test-docker-second:amd64'
        }
    }
    "(2.0.0,)" {
        distribution {
            docker = 'test-docker-third:arm64'
        }
    }
}

"TEST_COMPONENT_WITH_DOCKER_3" {
    componentOwner = "user9"
    groupId = "org.octopusden.octopus.test.docker"
    jira {
        projectKey = "TEST"
    }
    distribution {
        explicit = false
        external = true
        docker = 'test-docker-3:amd64'
    }
}

"TEST_COMPONENT_WITH_DOCKER_4" {
    componentOwner = "user9"
    groupId = "org.octopusden.octopus.test.docker"

    jira {
        projectKey = "TEST_DOCKER"
    }

    distribution {
        explicit = false
        external = true
    }

    "(,1.0.0]" {
        distribution {
            docker = 'test-docker-4'
        }
    }

    "(1.0.0, 2.0.0)" {
        distribution {
            docker = 'test-docker-4:amd64'
        }
    }
    "(2.0.0,)" {
        distribution {
            docker = 'test-docker-4:arm64'
        }
    }
}

"TEST_COMPONENT_WITH_DOCKER_5" {

    jira {
        projectKey = "TEST_DOCKER"
    }

    componentOwner = "user9"
    groupId = "org.octopusden.octopus.test.docker"
    distribution {
        explicit = false
        external = true
        docker = 'test-docker-5:amd64,test-docker-5:arm64'
    }
}
