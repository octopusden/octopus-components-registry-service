package org.octopusden.octopus.components.registry.api.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Dependencies DSL.
 * Keep configuration used by Update Dependencies Service.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Dependencies implements Cloneable {
    private final boolean autoUpdate;

    /**
     * Default constructor.
     * @param autoUpdate enabling or not component's dependencies auto updating
     */
    @JsonCreator
    public Dependencies(@JsonProperty("autoUpdate") boolean autoUpdate) {
        this.autoUpdate = autoUpdate;
    }

    /**
     * Get auto update state.
     * @return Returns true if component's dependencies can be updated by Update Dependencies Service
     */
    public boolean getAutoUpdate() {
        return autoUpdate;
    }

    @Override
    protected Object clone() throws CloneNotSupportedException {
        return super.clone();
    }
}
