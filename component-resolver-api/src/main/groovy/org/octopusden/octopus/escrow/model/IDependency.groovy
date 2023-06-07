package org.octopusden.octopus.escrow.model

interface IDependency {

    String getGroup()

    String getName()

    String getVersion()

    String getType()

    String getClassifier()
}
