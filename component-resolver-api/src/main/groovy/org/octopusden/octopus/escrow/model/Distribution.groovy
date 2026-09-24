package org.octopusden.octopus.escrow.model

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import groovy.transform.EqualsAndHashCode
import groovy.transform.TypeChecked

@TypeChecked
// Every field here is private, and @EqualsAndHashCode compares PROPERTIES — a private field is not
// one. Without includeFields the generated methods read no field at all and any two distributions
// compare equal (TD-023). `metaClass` must then be excluded by hand: includeFields also picks up
// that synthetic Groovy field, which is per-instance, so identical objects would never be equal.
@EqualsAndHashCode(includeFields = true, excludes = ['metaClass'])
@JsonInclude(JsonInclude.Include.NON_NULL)
class Distribution {
    @JsonProperty
    private final boolean explicit

    @JsonProperty
    private final boolean external

    @JsonProperty
    private final String GAV

    @JsonProperty
    private final String DEB

    @JsonProperty
    private final String RPM

    @JsonProperty
    private final String docker

    @JsonProperty
    private final String generic

    @JsonProperty
    private SecurityGroups securityGroups

    Distribution(boolean explicit, boolean external, String GAV, String DEB, String RPM, String docker, String generic, SecurityGroups securityGroups) {
        this.explicit = explicit
        this.external = external
        this.GAV = GAV
        this.DEB = DEB
        this.RPM = RPM
        this.docker = docker
        this.generic = generic
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

    String DEB() {
        return DEB
    }

    String docker() {
        return docker
    }

    String RPM() {
        return RPM
    }

    String generic() {
        return generic
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
                ", DEB='" + (DEB ?: "N/A") + '\'' +
                ", RPM='" + (RPM ?: "N/A") + '\'' +
                ", docker='" + (docker ?: "N/A") + '\'' +
                ", generic='" + (generic ?: "N/A") + '\'' +
                ", securityGroups='" + securityGroups + '\'' +
                '}'
    }
}
