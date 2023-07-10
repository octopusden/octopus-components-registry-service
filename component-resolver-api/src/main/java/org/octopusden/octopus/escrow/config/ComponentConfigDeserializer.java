package org.octopusden.octopus.escrow.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.octopusden.octopus.escrow.model.Distribution;
import org.octopusden.octopus.escrow.model.SecurityGroups;
import org.octopusden.octopus.escrow.model.VCSSettings;
import org.octopusden.octopus.releng.JiraComponentVersionDeserializer;
import org.octopusden.octopus.releng.dto.JiraComponent;
import org.octopusden.releng.versions.VersionNames;

import java.io.IOException;
import java.util.*;

public class ComponentConfigDeserializer extends JsonDeserializer<ComponentConfig> {

    private static final VCSSettingsDeserializer VCS_SETTINGS_DESERIALIZER = new VCSSettingsDeserializer();
    private final VersionNames versionNames;

    public ComponentConfigDeserializer(VersionNames versionNames) {
        this.versionNames = versionNames;
    }

    @Override
    public ComponentConfig deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) throws IOException {
        JsonNode treeNode = jsonParser.getCodec().readTree(jsonParser);
        Map<String, List<JiraComponentVersionRange>> projectKeyToJiraComponentVersionRangeMap =
                getKeyToJiraComponentVersionRangeMap("projectKeyToJiraComponentVersionRangeMap",
                        treeNode);
        Map<String, List<JiraComponentVersionRange>> componentNameToJiraComponentVersionRangeMap =
                getKeyToJiraComponentVersionRangeMap("componentNameToJiraComponentVersionRangeMap",
                        treeNode);
        return new ComponentConfig(projectKeyToJiraComponentVersionRangeMap, componentNameToJiraComponentVersionRangeMap);

    }

    private Map<String, List<JiraComponentVersionRange>> getKeyToJiraComponentVersionRangeMap(String key, JsonNode treeNode) {
        Map<String, List<JiraComponentVersionRange>> map = new HashMap<>();
        JsonNode projectKeysNode = treeNode.get(key);
        if (projectKeysNode == null) {
            return Collections.emptyMap();
        }
        Iterator<String> projectKeysIterator = projectKeysNode.fieldNames();
        while (projectKeysIterator.hasNext()) {
            String projectKey = projectKeysIterator.next();
            map.put(projectKey, getJiraComponentVersionRangeList(projectKeysNode.get(projectKey)));

        }
        return map;
    }

    private List<JiraComponentVersionRange> getJiraComponentVersionRangeList(JsonNode parentNode) {
        assert parentNode instanceof ArrayNode;
        List<JiraComponentVersionRange> versionRanges = new ArrayList<>();
        ArrayNode arrayNodes = (ArrayNode) parentNode;
        for (JsonNode node : arrayNodes) {
            versionRanges.add(getJiraComponentVersionRange(node));
        }
        return versionRanges;
    }

    private JiraComponentVersionRange getJiraComponentVersionRange(JsonNode node) {
        TextNode versionRange = (TextNode) node.get("versionRange");
        TextNode componentName = (TextNode) node.get("componentName");
        JiraComponentVersionDeserializer jiraComponentVersionDeserializer = new JiraComponentVersionDeserializer(versionNames);
        JiraComponent jiraComponent = jiraComponentVersionDeserializer.getJiraComponent(node);
        Distribution distribution = getDistribution(node);
        VCSSettings vcsSettings = VCS_SETTINGS_DESERIALIZER.deserialize(node.get("vcsSettings"));

        JiraComponentVersionRange.Builder builder = new JiraComponentVersionRange.Builder(versionNames)
                .componentName(componentName.textValue())
                .versionRange(versionRange.textValue())
                .jiraComponent(jiraComponent)
                .distribution(distribution)
                .vcsSettings(vcsSettings);
        return builder.build();
    }


    private Distribution getDistribution(JsonNode jsonNode) {
        JsonNode distributionNode = jsonNode.get("distribution");
        if (distributionNode != null) {
            BooleanNode explicit = (BooleanNode) distributionNode.get("explicit");
            BooleanNode external = (BooleanNode) distributionNode.get("external");
            TextNode gav = (TextNode) distributionNode.get("GAV");
            ObjectNode securityGroups = (ObjectNode) distributionNode.get("securityGroups");
            final JsonNode rdNode = securityGroups.get("read");

            final String read = rdNode != null ? rdNode.textValue() : null;

            return new Distribution(explicit.asBoolean(),
                    external.asBoolean(),
                    gav != null ? gav.textValue() : null,
                    new SecurityGroups(read)
            );
        }
        return null;
    }

}
