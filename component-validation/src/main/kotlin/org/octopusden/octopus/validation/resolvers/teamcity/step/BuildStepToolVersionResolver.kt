package org.octopusden.octopus.validation.resolvers.teamcity.step

import org.octopusden.octopus.validation.dto.teamcity.BuildStep
import org.octopusden.octopus.validation.dto.teamcity.StepType
import org.octopusden.octopus.validation.dto.teamcity.ToolVersion
import org.octopusden.octopus.validation.resolvers.teamcity.value.ValueVersionResolver

/**
 * Given a single [BuildStep], answer: what tool versions does it use? (Java and Maven for now —
 * see [ToolVersion].) Dispatch is by [StepType]: each runner family knows which of its own
 * parameters reveal a version, and which [ValueVersionResolver] to hand the (reference-resolved)
 * value to.
 */
interface BuildStepToolVersionResolver {
    fun resolve(step: BuildStep): Set<ToolVersion>

    /**
     * Whether this resolver has a dedicated strategy for [type] — lets a caller tell "nothing
     * relevant to inspect" apart from "inspected, found no version" without duplicating the
     * runner-type set the resolver itself already owns.
     */
    fun supports(type: StepType): Boolean
}
