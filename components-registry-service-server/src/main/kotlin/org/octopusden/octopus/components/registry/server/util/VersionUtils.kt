package org.octopusden.octopus.components.registry.server.util

import org.octopusden.releng.versions.NumericVersionFactory

fun String.formatVersion(versionNumericVersionFactory: NumericVersionFactory, version: String) =
    versionNumericVersionFactory.create(version).formatVersion(this)
