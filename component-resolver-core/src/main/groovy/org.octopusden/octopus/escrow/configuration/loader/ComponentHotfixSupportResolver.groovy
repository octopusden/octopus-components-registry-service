package org.octopusden.octopus.escrow.configuration.loader


import org.octopusden.octopus.escrow.model.VCSSettings

class ComponentHotfixSupportResolver {
    boolean isHotFixEnabled(VCSSettings vcsSettings) {
        return vcsSettings?.getVersionControlSystemRoots()?.any { it.hotfixBranch?.isEmpty() == false }
    }
}
