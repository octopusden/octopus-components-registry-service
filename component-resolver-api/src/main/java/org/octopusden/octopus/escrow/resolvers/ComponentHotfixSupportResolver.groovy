package org.octopusden.octopus.escrow.resolvers

import org.octopusden.octopus.escrow.model.VCSSettings

class ComponentHotfixSupportResolver {
    boolean isHotFixEnabled(VCSSettings vcsSettings) {
        return vcsSettings?.getVersionControlSystemRoots()?.any { it.hotfixBranch?.isEmpty() == false }
    }
}
