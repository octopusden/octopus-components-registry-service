package org.octopusden.octopus.components.registry.api.vcs;

import com.fasterxml.jackson.annotation.JsonProperty;

public interface CommonVersionControlSystem extends VersionControlSystem {
    @JsonProperty("url")
    String getUrl();

    /** Tag used for tagging RC/Release. */
    @JsonProperty("tag")
    String getTag();

    @JsonProperty("branch")
    String getBranch();

}
