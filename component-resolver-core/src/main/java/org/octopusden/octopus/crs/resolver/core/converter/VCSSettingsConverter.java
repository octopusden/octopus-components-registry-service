package org.octopusden.octopus.crs.resolver.core.converter;

import org.octopusden.octopus.components.registry.api.beans.ExternalVersionControlSystemBean;
import org.octopusden.octopus.components.registry.api.beans.GitVersionControlSystemBean;
import org.octopusden.octopus.components.registry.api.beans.MultiplyVersionControlSystemBean;
import org.octopusden.octopus.components.registry.api.vcs.VersionControlSystem;
import org.octopusden.octopus.escrow.RepositoryType;
import org.octopusden.octopus.escrow.model.VCSSettings;

import java.util.List;
import java.util.stream.Collectors;

public final class VCSSettingsConverter extends AbstractConverter<VCSSettings, VersionControlSystem> {
    public VCSSettingsConverter() {
        super(vcsSettings -> {
            if (vcsSettings.externalRegistry()) {
                return new ExternalVersionControlSystemBean();
            }
            if (vcsSettings.hasNoConfiguredVCSRoot()) {
                //TODO Check if it is real case, if not then throw exception
                return null;
            }

            final List<VersionControlSystem> vcs = vcsSettings.getVersionControlSystemRoots().stream().map(vcsRoot -> {
                if (vcsRoot.getRepositoryType() != RepositoryType.GIT) {
                    return new ExternalVersionControlSystemBean();
                }
                return new GitVersionControlSystemBean(vcsRoot.getVcsPath(), vcsRoot.getTag(), vcsRoot.getBranch());
            }).collect(Collectors.toList());
            if (vcs.size() == 1) {
                return vcs.get(0);
            }
            final MultiplyVersionControlSystemBean multiplyVcs = new MultiplyVersionControlSystemBean();
            multiplyVcs.setVcs(vcs);
            return multiplyVcs;
        });
    }

}
