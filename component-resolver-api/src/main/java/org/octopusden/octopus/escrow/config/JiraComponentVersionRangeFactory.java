package org.octopusden.octopus.escrow.config;

import org.octopusden.octopus.escrow.model.Distribution;
import org.octopusden.octopus.escrow.model.VCSSettings;
import org.octopusden.octopus.releng.JiraComponentVersionFormatter;
import org.octopusden.octopus.releng.dto.ComponentVersion;
import org.octopusden.octopus.releng.dto.JiraComponent;
import org.octopusden.octopus.releng.dto.JiraComponentVersion;
import org.octopusden.releng.versions.VersionNames;

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
        JiraComponentVersion jiraComponentVersion = JiraComponentVersion.builder(jiraComponentVersionFormatter)
                .componentVersion(ComponentVersion.create(componentName, versionRange))
                .component(jiraComponent)
                .build();

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
