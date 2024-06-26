import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.BuildSystem.PROVIDED
import static org.octopusden.octopus.escrow.RepositoryType.MERCURIAL

final DEFAULT_TAG = "$module-$version"
final ANY_ARTIFACT = /[\w-\.]+/
final ALL_VERSIONS = "(,0),[0,)"

Defaults {
    system = "NONE"
    releasesInDefaultBranch = true
    solution = false
    repositoryType = MERCURIAL
    buildSystem = MAVEN;
    tag = DEFAULT_TAG;
    artifactId = ANY_ARTIFACT
    jira {
        releaseVersionFormat = '$major.$minor.$service'
        majorVersionFormat = '$major.$minor'
        customer {
            versionFormat = '$versionPrefix-$baseVersionFormat'
        }

    }
}


bcomponent {
    componentOwner = "user1"
    vcsUrl = "ssh://hg@mercurial/bcomponent"
    buildSystem = MAVEN
    repositoryType = MERCURIAL
    groupId = "org.octopusden.octopus.bcomponent"
    artifactId = "builder"
    tag = '$module.$version'
    jiraProjectKey = "bs-core-jira"
    jiraMajorVersionFormat = '$major'
    jiraReleaseVersionFormat = '$major.$minor'
    zenit = "1.6"

}

commoncomponent {
    "(10,)" {
        groupId = "org.octopusden.octopus.system"
        artifactId = "commoncomponent,hello,bonjur"
        vcsUrl = "ssh://hg@mercurial/o2/other/commoncomponent"
        zenit = "asd"
        branch = "default"
        tag = "commoncomponent-tag"
        jira {
            projectKey = "system"
            majorVersionFormat = '$major.$minor'
            releaseVersionFormat = '$major.$minor.$service'
            buildVersionFormat = '$major.$minor.$service-$build'
            displayName = "SYSTEM_CUSTOMER"
            customer {
                versionPrefix = "system"
                versionFormat = '$versionPrefix.$baseVersionFormat'
            }
        }
    }
}



server {
    "[1.5,)" {
        vcsUrl = "as-vcs-url"
        tag = "as-tag"
        branch = "as-branch"
        groupId = "org.octopusden.octopus.server"
        artifactId = "server"
        displayName = "APP_CUSTOMER"

        jira {
            projectKey = "AS"
            majorVersionFormat = '$major.$minor'
            releaseVersionFormat = '$major.$minor.$service'
            customer {
                versionPrefix = "app"
            }
        }
    }
}


octopusweb {
    "(,0),[0,)" {
        groupId = "org.octopusden.octopus.octopusweb"
        artifactId = ANY_ARTIFACT
        buildSystem = PROVIDED

        jiraProjectKey = "WCOMPONENT"
        jiraMajorVersionFormat = '$major.$minor'
        jiraReleaseVersionFormat = '$major.$minor.$service'


        customTag = "asd"
        customGroup2 {
            hello = "DENIS"
        }
    }

    customGroup {
        hello = "ASD"
    }
}

component {
    componentOwner = "user1"
    repositoryType = MERCURIAL
    vcsUrl = "component-vcs-url"
    tag = "component-tag"
    branch = "component-branch"

    groupId = "org.octopusden.octopus.componentc"
    artifactId = ANY_ARTIFACT
    buildSystem = PROVIDED

    jira {
        projectKey = "TEST_COMPONENT2"
        lineVersionFormat = '$major02.$minor02'
        majorVersionFormat = '$major02.$minorC.$serviceC'
        releaseVersionFormat = '$major02.$minor02.$service02.$fix02'
        buildVersionFormat = '$major02.$minor02.$service02.$fix02-$build'
        displayName = "TEST_COMPONENT2_CUSTOMER"

    }

    "[0, 1)" {
        jira {
            customer {
                versionPrefix = "customer-component"
            }
        }

    }

    "[1,2)" {
        jira {
            component {
                versionPrefix = "component-component"
            }
        }

    }
}

sms_component {
    "(,0),[0,)" {
        groupId = "org.octopusden.octopus.sms_component"
        artifactId = ANY_ARTIFACT
        buildSystem = PROVIDED
    }
}

"test-project" {
    "[1.0]" {
        jira {
            projectKey = "TEST"
            majorVersionFormat = '$major.$minor'
            releaseVersionFormat = '$major.$minor.$service'
        }
    }
    "[2.0]" {
        repositoryType = MERCURIAL
        vcsUrl = "test-url"
        tag = "test-tag"
        branch = "test-branch"
    }
}


"test-branch" {
    "$ALL_VERSIONS" {
        repositoryType = MERCURIAL
        vcsUrl = "url"
        tag = "test-tag"
        branch = 'BRANCH-$major.$minor'

        jira {
            projectKey = "BRANCH"
            majorVersionFormat = '$major.$minor'
            releaseVersionFormat = '$major.$minor.$service'
        }
    }
}


"TESTONE" {
    groupId = "org.octopusden.octopus.test"
    artifactId = "test2"
    vcsUrl = "ssh://hg@mercurial/o2/other/commoncomponent"
    jira {
        projectKey = "TESTONE"
        majorVersionFormat = '$major'
        releaseVersionFormat = '$major.$minor'
        buildVersionFormat = '$major.$minor.$build'
        displayName = "TESTONE DISPLAY NAME WITH VERSIONS-API"
    }

    components {
        "versions-api" {
            jira {
                displayName = "VERSIONS API COMPONENT"
                component {
                    versionPrefix = "versions-api"
                }
            }
        }
    }
}




"TEST_COMPONENT8" {
    groupId = "org.octopusden.octopus.componentp.acard"
    vcsUrl = "ssh://hg@mercurial//componentp/implementations/acard"
    tag = 'acard-$version'
    jira {
        projectKey = "componentp"
        lineVersionFormat = '$major'
        majorVersionFormat = '$major'
        releaseVersionFormat = '$major.$minor.$service'
        buildVersionFormat = '$major.$minor.$service-$build'
        displayName = "MobileWeb Banking" //Header for client release notes
        customer {
            versionPrefix = "acard"
        }
    }
    "(1.0.34, 2.0)" {
        branch = "acard-1.0"
    }
    "(2.0,)" {
    }
}

"component-info-inheritance" {
    jira {
        projectKey = "CII"
        lineVersionFormat = '$major'
        majorVersionFormat = '$major'
        releaseVersionFormat = '$major.$minor.$service'
        buildVersionFormat = '$major.$minor.$service-$build'
        displayName = "Component Info Inheritance in Version Ranges"
        component {
            versionPrefix = "cii"
        }
    }
    "[1.0,2.0)" {
        jira {
            displayName = "Component Info Inheritance V1"
            releaseVersionFormat = '$major.$minor.$service'
        }
    }

    "[2.0,)" {
        jira {
            displayName = "Component Info Inheritance V2"
            releaseVersionFormat = '$major.$minor.$service'
            component {
                versionFormat = '$baseVersionFormat.$versionPrefix'
            }
        }
    }
}
