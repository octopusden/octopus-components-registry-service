package org.octopusden.octopus.escrow.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.octopusden.octopus.escrow.model.Distribution;
import org.octopusden.octopus.escrow.model.VCSSettings;
import org.octopusden.octopus.releng.dto.ComponentVersion;
import org.octopusden.octopus.releng.dto.JiraComponent;
import org.octopusden.octopus.releng.dto.JiraComponentVersion;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class JiraComponentVersionRange {

    @JsonProperty
    private final String componentName;

    @JsonProperty
    private final String versionRange;

    @JsonProperty
    private final JiraComponent jiraComponent;

    @JsonProperty
    private final Distribution distribution;

    @JsonProperty
    private final VCSSettings vcsSettings;

    @JsonCreator
    public JiraComponentVersionRange(@JsonProperty("componentName") String componentName, @JsonProperty("versionRange") String versionRange,
                                     @JsonProperty("component") JiraComponent component, @JsonProperty("distribution") Distribution distribution,
                                     @JsonProperty("vcsSettings") VCSSettings vcsSettings) {
        this.componentName = componentName;
        this.versionRange = versionRange;
        this.jiraComponent = component;
        this.distribution = distribution;
        this.vcsSettings = vcsSettings;
    }

    public JiraComponent getComponent() {
        return jiraComponent;
    }

    public String getVersionRange() {
        return versionRange;
    }

    public JiraComponentVersion getJiraComponentVersion(String version) {
        return new JiraComponentVersion(ComponentVersion.create(componentName, version), jiraComponent);
    }

    public String getComponentName() {
        return componentName;
    }

    public Distribution getDistribution() {
        return distribution;
    }

    public VCSSettings getVcsSettings() {
        return vcsSettings;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        JiraComponentVersionRange that = (JiraComponentVersionRange) o;

        return new EqualsBuilder()
                .append(versionRange, that.versionRange)
                .append(jiraComponent, that.jiraComponent)
                .append(distribution, that.distribution)
                .append(vcsSettings, that.vcsSettings)
                .isEquals();
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder()
                .append(versionRange)
                .append(jiraComponent)
                .append(distribution)
                .append(vcsSettings)
                .toHashCode();
    }

    @Override
    public String toString() {
        return "JiraComponentVersionRange{" +
                "componentName='" + componentName + '\'' +
                ", versionRange='" + versionRange + '\'' +
                ", jiraComponent=" + jiraComponent +
                ", distribution=" + distribution +
                ", vcsSettings=" + vcsSettings +
                '}';
    }
}
