package org.octopusden.octopus.escrow.config;

import org.octopusden.octopus.escrow.model.Distribution;
import org.octopusden.octopus.escrow.model.VCSSettings;
import org.octopusden.octopus.releng.JiraComponentVersionFormatter;
import org.octopusden.octopus.releng.dto.ComponentVersion;
import org.octopusden.octopus.releng.dto.JiraComponent;
import org.octopusden.octopus.releng.dto.JiraComponentVersion;
import org.octopusden.releng.versions.VersionNames;
import org.octopusden.octopus.escrow.resolvers.ComponentHotfixSupportResolver;

public class JiraComponentVersionRangeFactory {

    private final VersionNames versionNames;

    public JiraComponentVersionRangeFactory(VersionNames versionNames) {
        this.versionNames = versionNames;
    }

    public JiraComponentVersionRange create(
            String componentName,
            String versionRange,
            JiraComponent jiraComponent,
            Distribution distribution,
            VCSSettings vcsSettings
    ) {
        JiraComponentVersionFormatter jiraComponentVersionFormatter = new JiraComponentVersionFormatter(versionNames);
        ComponentHotfixSupportResolver componentHotfixSupportResolver = new ComponentHotfixSupportResolver();

        JiraComponentVersion jiraComponentVersion = new JiraComponentVersion(
                ComponentVersion.create(componentName, versionRange),
                jiraComponent,
                jiraComponentVersionFormatter,
                componentHotfixSupportResolver.isHotFixEnabled(vcsSettings)
        );

        return new JiraComponentVersionRange(
                componentName,
                versionRange,
                jiraComponent,
                distribution,
                vcsSettings,
                jiraComponentVersion
        );
    }
}
