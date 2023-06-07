package org.octopusden.octopus.escrow.model

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import groovy.transform.EqualsAndHashCode
import groovy.transform.TypeChecked
import org.octopusden.octopus.escrow.model.SecurityGroups

@TypeChecked
@EqualsAndHashCode
@JsonInclude(JsonInclude.Include.NON_NULL)
class Distribution {
    @JsonProperty
    private final boolean explicit

    @JsonProperty
    private final boolean external

    @JsonProperty
    private final String GAV

    @JsonProperty
    private SecurityGroups securityGroups

    Distribution(boolean explicit, boolean external, String GAV, SecurityGroups securityGroups) {
        this.explicit = explicit
        this.external = external
        this.GAV = GAV
        this.securityGroups = securityGroups
    }

    boolean external() {
        return external
    }

    boolean explicit() {
        return explicit
    }

    String GAV() {
        return GAV
    }

    SecurityGroups getSecurityGroups() {
        return securityGroups
    }

    @Override
    String toString() {
        return "Distribution{" +
                "explicit=" + explicit +
                ", external=" + external +
                ", GAV='" + (GAV ?: "N/A") + '\'' +
                ", securityGroups='" + securityGroups + '\'' +
                '}'
    }
}
