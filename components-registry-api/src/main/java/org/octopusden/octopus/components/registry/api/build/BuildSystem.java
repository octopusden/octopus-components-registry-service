package org.octopusden.octopus.components.registry.api.build;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.octopusden.octopus.components.registry.api.beans.ClassicBuildSystem;
import org.octopusden.octopus.components.registry.api.enums.BuildSystemType;

import java.util.Optional;

import static com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY;
import static com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NAME;

/**
 * Component's build system configuration
 */
@JsonTypeInfo(use = NAME, include = PROPERTY)
@JsonSubTypes({
        @JsonSubTypes.Type(value = ClassicBuildSystem.class, name = "classic")
})
public interface BuildSystem {
    /**
     *
     * @return Returns build system type
     */
    BuildSystemType getType();

    /**
     * Get build system version.
     * For Gradle based builds has to be ignored if <a href="https://docs.gradle.org/current/userguide/gradle_wrapper.html">Gradle Wrapper</a> is configured in project.
     * @return Returns build system version if specified, {@link Optional#empty()} otherwise
     */
    Optional<String> getVersion();
}
