package org.octopusden.octopus.escrow.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.octopusden.octopus.escrow.model.Distribution;
import org.octopusden.octopus.escrow.model.VCSSettings;
import org.octopusden.octopus.releng.JiraComponentVersionFormatter;
import org.octopusden.octopus.releng.dto.ComponentVersion;
import org.octopusden.octopus.releng.dto.JiraComponent;
import org.octopusden.octopus.releng.dto.JiraComponentVersion;
import org.octopusden.releng.versions.VersionNames;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class JiraComponentVersionRange {

    @JsonProperty
    public final String componentName;

    @JsonProperty
    public final String versionRange;

    @JsonProperty("component")
    public final JiraComponent jiraComponent;

    @JsonProperty
    public final Distribution distribution;

    @JsonProperty
    public final VCSSettings vcsSettings;

    public final JiraComponentVersion jiraComponentVersion;

    public static Builder builder(VersionNames versionNames) {
        return new Builder(versionNames);
    }
    @JsonPOJOBuilder(withPrefix = "")
    public static class Builder {

        private String componentName;
        private String versionRange;
        private Distribution distribution;
        private VCSSettings vcsSettings;
        public JiraComponent jiraComponent;
        private JiraComponentVersion jiraComponentVersion;

        private final VersionNames versionNames;

        public Builder(VersionNames versionNames) {
            this.versionNames = versionNames;
        }

        public JiraComponentVersionRange build() {
            JiraComponentVersionFormatter jiraComponentVersionFormatter = new JiraComponentVersionFormatter(versionNames);
            jiraComponentVersion = JiraComponentVersion.builder(jiraComponentVersionFormatter)
                    .componentVersion(ComponentVersion.create(componentName, versionRange))
                    .component(jiraComponent)
                    .build();
            return new JiraComponentVersionRange(this);
        }

        public Builder componentName(String componentName) {
            this.componentName = componentName;
            return this;
        }

        public Builder versionRange(String versionRange) {
            this.versionRange = versionRange;
            return this;
        }

        public Builder distribution(Distribution distribution) {
            this.distribution = distribution;
            return this;
        }

        public Builder vcsSettings(VCSSettings vcsSettings) {
            this.vcsSettings = vcsSettings;
            return this;
        }

        public Builder jiraComponent(JiraComponent jiraComponent) {
            this.jiraComponent = jiraComponent;
            return this;
        }
    }

    private JiraComponentVersionRange(Builder builder) {
        this.componentName = builder.componentName;
        this.versionRange = builder.versionRange;
        this.jiraComponent = builder.jiraComponent;
        this.distribution = builder.distribution;
        this.vcsSettings = builder.vcsSettings;
        this.jiraComponentVersion = builder.jiraComponentVersion;
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
