package org.octopusden.octopus.escrow.configuration.loader

import groovy.transform.TypeChecked

@TypeChecked
enum LoaderInheritanceType {

    DEFAULT(false),
    COMPONENT(false),
    VERSION_RANGE(true),
    SUB_COMPONENT(false)

    public final boolean octopusVersionInherit

    LoaderInheritanceType(boolean octopusVersionInherit) {
        this.octopusVersionInherit = octopusVersionInherit
    }
}
