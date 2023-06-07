package org.octopusden.octopus.escrow.model

import groovy.transform.AutoClone
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import groovy.transform.TupleConstructor

@TupleConstructor
@EqualsAndHashCode
@ToString
@AutoClone
class Tool {
    String name
    String escrowEnvironmentVariable
    String sourceLocation
    String targetLocation
    String installScript
}
