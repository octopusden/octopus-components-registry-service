package org.octopusden.octopus.escrow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.octopusden.octopus.escrow.config.ComponentConfig;
import org.octopusden.octopus.escrow.config.ComponentConfigParser;
import org.octopusden.octopus.escrow.config.JiraComponentVersionRange;
import org.octopusden.octopus.escrow.model.Distribution;
import org.octopusden.octopus.escrow.model.SecurityGroups;
import org.octopusden.octopus.escrow.model.VCSSettings;
import org.octopusden.octopus.releng.dto.ComponentInfo;
import org.octopusden.octopus.releng.dto.JiraComponent;
import org.octopusden.releng.versions.ComponentVersionFormat;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;

public class ComponentConfigParserTest {

    public static final ComponentVersionFormat COMPONENT_VERSION_FORMAT_1 = ComponentVersionFormat.create("$major.$minor.$service", "$major.$minor.$service-$fix");
    private static final String COMPONENT = "WCOMPONENT";
    private static final String CLIENT = "client";
    private static final ComponentVersionFormat COMPONENT_VERSION_FORMAT_2 = ComponentVersionFormat.create("$major.$minor", "$major.$minor.$service");

    @Test
    public void testParseFromSerializedObject() throws InvalidVersionSpecificationException, IOException {
        ComponentConfig componentConfig = getComponentConfig();
        String componentConfigJson = serialize(componentConfig);
        ComponentConfig newComponentConfig = new ComponentConfigParser().parse(componentConfigJson);
        assertEquals(componentConfig, newComponentConfig);
    }

    @NotNull
    private ComponentConfig getComponentConfig() throws InvalidVersionSpecificationException {
        Map<String, List<JiraComponentVersionRange>> projectKeyMap = new HashMap<>();
        Map<String, List<JiraComponentVersionRange>> componentNameMap = new HashMap<>();
        projectKeyMap.put(COMPONENT, getJiraComponentVersionRangeList());
        projectKeyMap.put(CLIENT, getJiraComponentVersionRangeListWithComponentInfo());

        projectKeyMap.put("octopusweb", getJiraComponentVersionRangeList());
        projectKeyMap.put("client", getJiraComponentVersionRangeListWithComponentInfo());
        return new ComponentConfig(projectKeyMap, componentNameMap);
    }

    private String serialize(ComponentConfig componentConfig) throws JsonProcessingException {
        ObjectMapper objectMapper = getObjectMapper();
        return objectMapper.writeValueAsString(componentConfig);
    }

    @NotNull
    private ObjectMapper getObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        return objectMapper;
    }

    private List<JiraComponentVersionRange> getJiraComponentVersionRangeList() throws InvalidVersionSpecificationException {
        return Arrays.asList(getJiraComponentVersionRange("octopusweb", "[2.1,)", COMPONENT, COMPONENT_VERSION_FORMAT_1, null, null,
                VCSSettings.createEmpty(), false),
                getJiraComponentVersionRange("octopusweb", "[,2.1)", COMPONENT, COMPONENT_VERSION_FORMAT_2, null, null,
                        TestHelper.createTestVCSSettings(), false));
    }


    private List<JiraComponentVersionRange> getJiraComponentVersionRangeListWithComponentInfo() {
        ComponentInfo componentInfo = new ComponentInfo(CLIENT, "$versionPrefix-$baseVersionFormat");
        return Collections.singletonList(getJiraComponentVersionRange("client", "(,0),[0,)",
                "CLIENT", COMPONENT_VERSION_FORMAT_1, componentInfo, new Distribution(true, false, null, new SecurityGroups(null)), VCSSettings.createEmpty(), false));
    }

    private JiraComponentVersionRange getJiraComponentVersionRange(String componentName, String versionRange, String projectKey,
                                                                   ComponentVersionFormat componentVersionFormat,
                                                                   ComponentInfo componentInfo, Distribution distribution,
                                                                   VCSSettings vcsSettings, boolean technical) {
        JiraComponent jiraComponent = getJiraComponent(projectKey, componentVersionFormat, componentInfo, technical);
        return new JiraComponentVersionRange(componentName, versionRange, jiraComponent, distribution,
                vcsSettings);
    }

    private JiraComponent getJiraComponent(String projectKey, ComponentVersionFormat componentVersionFormat, ComponentInfo componentInfo, boolean technical) {
        return new JiraComponent(projectKey, projectKey, componentVersionFormat, componentInfo, technical);
    }
}
