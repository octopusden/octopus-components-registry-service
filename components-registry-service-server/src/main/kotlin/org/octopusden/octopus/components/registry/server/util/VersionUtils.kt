package org.octopusden.octopus.components.registry.server.util

import org.octopusden.releng.versions.NumericVersion

fun String.formatVersion(version: String) = NumericVersion.parse(version).formatVersion(this)
