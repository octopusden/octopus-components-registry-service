package org.octopusden.octopus.escrow.model

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import groovy.transform.EqualsAndHashCode
import groovy.transform.TypeChecked

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
    private final String DEB

    @JsonProperty
    private final String RPM

    @JsonProperty
    private final String docker

    @JsonProperty
    private SecurityGroups securityGroups

    Distribution(boolean explicit, boolean external, String GAV, String DEB, String RPM, SecurityGroups securityGroups, String docker) {
        this.explicit = explicit
        this.external = external
        this.GAV = GAV
        this.DEB = DEB
        this.RPM = RPM
        this.docker = docker
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
                ", securityGroups='" + securityGroups + '\'' +
                ", docker='" + (docker ?: "N/A") + '\'' +
                '}'
    }
}
