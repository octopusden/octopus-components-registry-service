package org.octopusden.octopus.escrow.model

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import groovy.transform.EqualsAndHashCode
import groovy.transform.TypeChecked
import org.apache.commons.lang3.Validate

@TypeChecked
@EqualsAndHashCode
@JsonInclude(JsonInclude.Include.NON_NULL)
class Doc {

    @JsonProperty
    private final String component
    @JsonProperty
    private final String majorVersion

    Doc(String component, String majorVersion) {
        Validate.notBlank(component, "Doc.component is not specified")
        this.component = component
        this.majorVersion = majorVersion
    }

    String component() {
        return component
    }

    String majorVersion() {
        return majorVersion
    }

    @Override
    public String toString() {
        return "Doc{" +
                "component='" + component + '\'' +
                ", majorVersion='" + majorVersion + '\'' +
                '}';
    }
}
