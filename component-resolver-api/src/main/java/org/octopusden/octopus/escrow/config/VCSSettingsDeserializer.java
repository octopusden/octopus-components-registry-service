package org.octopusden.octopus.escrow.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.octopusden.octopus.escrow.RepositoryType;
import org.octopusden.octopus.escrow.model.VCSSettings;
import org.octopusden.octopus.escrow.model.VersionControlSystemRoot;

import java.util.ArrayList;

class VCSSettingsDeserializer {

    static VersionControlSystemRoot getVCSRoot(JsonNode node) {
        RepositoryType repositoryType = RepositoryType.valueOf(node.get("repositoryType").asText());
        String vcsPath = node.get("vcsPath").asText();
        String tag = node.has("tag") ? node.get("tag").asText() : null;
        String branch = node.get("branch").asText();
        String name = node.get("name").asText();
        return VersionControlSystemRoot.create(name, repositoryType, vcsPath, tag, branch);
    }

    VCSSettings deserialize(JsonNode vcsSettingsNode) {
        if (vcsSettingsNode == null) {
            return VCSSettings.createEmpty();
        } else {
            JsonNode externalRegistry = vcsSettingsNode.get("externalRegistry");
            ArrayList<VersionControlSystemRoot> vcsRoots = getVersionControlSystemRoots(vcsSettingsNode);
            return VCSSettings.create(externalRegistry != null ? externalRegistry.textValue() : null, vcsRoots);
        }
    }

    private ArrayList<VersionControlSystemRoot> getVersionControlSystemRoots(JsonNode vcsSettingsNode) {
        ArrayNode vcsRootNodes = (ArrayNode) vcsSettingsNode.get("versionControlSystemRoots");
        ArrayList<VersionControlSystemRoot> vcsRoots = new ArrayList<>();
        for (JsonNode vcsRootNode : vcsRootNodes) {
            vcsRoots.add(getVCSRoot(vcsRootNode));
        }
        return vcsRoots;
    }
}
