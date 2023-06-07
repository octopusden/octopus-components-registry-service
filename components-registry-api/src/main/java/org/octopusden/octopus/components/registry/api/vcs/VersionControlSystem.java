package org.octopusden.octopus.components.registry.api.vcs;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.octopusden.octopus.components.registry.api.beans.ExternalVersionControlSystemBean;
import org.octopusden.octopus.components.registry.api.beans.GitVersionControlSystemBean;
import org.octopusden.octopus.components.registry.api.beans.MultiplyVersionControlSystemBean;
import org.octopusden.octopus.components.registry.api.enums.VersionControlSystemType;

import static com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY;
import static com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NAME;

/**
 * Version control type DTO.
 */
@JsonTypeInfo(use = NAME, include = PROPERTY)
@JsonSubTypes({
        @JsonSubTypes.Type(value = GitVersionControlSystemBean.class, name = "git"),
        @JsonSubTypes.Type(value = ExternalVersionControlSystemBean.class, name = "external"),
        @JsonSubTypes.Type(value = MultiplyVersionControlSystemBean.class, name = "multiply")
})
public interface VersionControlSystem {
    /**
     * Get VCS type.
     * @return Returns VCS type
     */
    VersionControlSystemType getType();
}
