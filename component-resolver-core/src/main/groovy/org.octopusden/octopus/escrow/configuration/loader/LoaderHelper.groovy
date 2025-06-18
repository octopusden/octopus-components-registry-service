package org.octopusden.octopus.escrow.configuration.loader


import org.octopusden.octopus.escrow.model.VCSSettings

class LoaderHelper {
    static boolean isHotFixEnabled(VCSSettings vcsSettings) {
        return vcsSettings?.getVersionControlSystemRoots()?.any { it.hotfixBranch?.isEmpty() == false }
    }
}
