"TEST_COMPONENT3" {
    componentDisplayName = "3-D Secure"
    componentOwner = "user4"
    releaseManager = "user4"
    securityChampion = "user4"
    vcsUrl = 'ssh://hg@mercurial/tdsecure'
    groupId = "org.octopusden.octopus.test"
    tag = 'octopustds-$version'
//    branch = "default"
    jira {
        projectKey = "TDS"
    }
    build {
        javaVersion = "1.8"
    }
    distribution {
        GAV = "org.octopusden.octopus.test:octopusmpi:war," +
                "org.octopusden.octopus.test:octopusacs:war," +
                'org.octopusden.octopus.test:demo:war,file:///${env.CONF_PATH}/NDC_DDC_Configuration_Builder/${major}.${minor}/NDC-DDC-Configuration-Builder-${major}.${minor}.${service}.exe'
        explicit = true
        external = true
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

Component {
    componentDisplayName = "Component display name"
    componentOwner = "user5"
    releaseManager = "user5"
    securityChampion = "user5"
    jira {
        projectKey = "TTTT"
    }
    vcsSettings {
        tag = 'TTTT-R-$version'
        vcsUrl = 'ssh://hg@mercurial/o2/ts'
        branch = "default"
    }

    groupId = "org.octopusden.octopus.ts"
    artifactId = ANY_ARTIFACT
    build {
        requiredProject = true
        projectVersion = "03.42"
        mavenVersion = "3.3.9"
    }

    "[1.1,1.1.63),(1.1.63,1.1.99)" {
        tag = 'TS-$version'
    }
    "[1.1.63]" {
        tag = 'ESCROW_TS_1.1.63-0003'
    }
    "[1.1.156]" {
        tag = 'TTTT-1.1.156-escrow'
    }
    "[1.1.158]" {
        tag = 'TTTT-$version'
        build {
            systemProperties = '-Dcomponent.version=3.42.30-0011 -Dpkgj.version=3.42.30-0011 -Dcomponent.version=3.42.30-0011 -Dpackages.version=3.42.30-0011'
        }
    }
    "[1.1.99, 1.1.155],[1.1.157],[1.1.159,1.1.210],(1.1.211,1.1.214],(1.1.215,1.1.230]" {
        tag = 'TTTT-$version'
    }

    "[1.1.210-1, 1.1.211), [1.1.214-1, 1.1.215), [1.1.231,1.1.235)" {
        jira {
            releaseVersionFormat = '$major.$minor.$service-$fix'
        }
        tag = 'TTTT-$version'
    }

    "[1.1.235,1.1.273), [1.1.273-1, 1.1.273-5000], [1.1.274,1.1.306), [1.1.307,)" {
        jira {
            releaseVersionFormat = '$major.$minor.$service-$fix'
        }
    }

    "[1.1.211], [1.1.215]" {

    }

    "[1.1.273]" {
        tag = 'ESCROW-1.1.273'
    }

    "[1.1.306-1,1.1.307)" {
        jira {
            releaseVersionFormat = '$major.$minor.$service-$fix'
        }
        tag = 'TRSW-R-1.1.306-escrow-2'
    }

    distribution {
        explicit = true
        external = true
        GAV='file:///${env.CONF_PATH}/tscomponent/Core/${version}/ts-${version}.zip?artifactId=ts'
    }
}
