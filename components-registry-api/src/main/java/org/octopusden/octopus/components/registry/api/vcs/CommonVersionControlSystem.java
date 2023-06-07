package org.octopusden.octopus.components.registry.api.vcs;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Common version control system.
 */
public interface CommonVersionControlSystem extends VersionControlSystem {
    /**
     * Repository url.
     * @return Returns repository url
     */
    @JsonProperty("url")
    String getUrl();

    /**
     * Tag used for tagging RC/Release.
     * @return Returns tag
     */
    @JsonProperty("tag")
    String getTag();

    /**
     * Branch.
     * @return Returns branch
     */
    @JsonProperty("branch")
    String getBranch();

}
