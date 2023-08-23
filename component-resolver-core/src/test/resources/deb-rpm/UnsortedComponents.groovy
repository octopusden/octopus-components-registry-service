import static org.octopusden.octopus.escrow.BuildSystem.*

logcomp {
    componentDisplayName = "logcomp"
    releaseManager = "nezlobin"
    componentOwner = "nezlobin"
    securityChampion = "nezlobin"

    buildSystem = PROVIDED

    groupId = 'org.octopusden.octopus.logcomp' //TODO: is it really required for deb / rpm component?

    vcsSettings {
        vcsUrl = 'ssh://git@gitlab:log/logcomp.git'
        tag = 'logcomp-$version'
    }

    "[3,4)" {
        distribution {
            GAV = 'org.octopusden.octopus.logcomp:logcomp'
        }
    }

    "[4,5)" {
		distribution {
            RPM = 'logcomp_${version}.el8.noarch.rpm'
        }
    }

    jira {
        projectKey = "LOG"
        majorVersionFormat = '$major.$minor.$service'
        releaseVersionFormat = '$major.$minor.$service-$fix'
    }

    distribution {
        explicit = true
        external = true
		DEB = 'logcomp_${version}-1_amd64.deb'
    }
}