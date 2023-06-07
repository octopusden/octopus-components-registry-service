package org.octopusden.octopus.escrow.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ComponentConfig {

    private final Map<String, List<JiraComponentVersionRange>> projectKeyToJiraComponentVersionRangeMap;
    private final Map<String, List<JiraComponentVersionRange>> componentNameToJiraComponentVersionRangeMap;

    public ComponentConfig(Map<String, List<JiraComponentVersionRange>> projectKeyToJiraComponentVersionRangeMap,
                           Map<String, List<JiraComponentVersionRange>> componentNameToJiraComponentVersionRangeMap) {
        this.projectKeyToJiraComponentVersionRangeMap = projectKeyToJiraComponentVersionRangeMap;
        this.componentNameToJiraComponentVersionRangeMap = componentNameToJiraComponentVersionRangeMap;
    }

    public Map<String, List<JiraComponentVersionRange>> getProjectKeyToJiraComponentVersionRangeMap() {
        return projectKeyToJiraComponentVersionRangeMap;
    }

    public Map<String, List<JiraComponentVersionRange>> getComponentNameToJiraComponentVersionRangeMap() {
        return componentNameToJiraComponentVersionRangeMap;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ComponentConfig)) {
            return false;
        }

        ComponentConfig that = (ComponentConfig) o;

        return new EqualsBuilder()
                .append(projectKeyToJiraComponentVersionRangeMap, that.projectKeyToJiraComponentVersionRangeMap)
                .append(componentNameToJiraComponentVersionRangeMap, that.componentNameToJiraComponentVersionRangeMap)
                .isEquals();
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder()
                .append(projectKeyToJiraComponentVersionRangeMap)
                .append(componentNameToJiraComponentVersionRangeMap)
                .toHashCode();
    }

    @Override
    public String toString() {
        return "ComponentConfig{" + "projectKeyToJiraComponentVersionRangeMap=" + projectKeyToJiraComponentVersionRangeMap +
                "componentNameToJiraComponentVersionRangeMap=" + componentNameToJiraComponentVersionRangeMap +
                '}';
    }
}
