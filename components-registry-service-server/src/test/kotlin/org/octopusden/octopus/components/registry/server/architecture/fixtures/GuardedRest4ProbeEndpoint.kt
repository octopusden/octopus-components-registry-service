package org.octopusden.octopus.components.registry.server.architecture.fixtures

import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping

/**
 * Fixture for `ArchitectureFitnessRegressionTest`: a v4 endpoint mapped under `rest/api/4` that
 * DOES declare an authorization policy — the security rule must accept it (green case).
 * See [Rest4ProbeEndpoint] for why `@RestController` is omitted.
 */
@Suppress("FunctionOnlyReturningConstant") // trivial fixture endpoint; body is irrelevant to the rule
@RequestMapping("rest/api/4/probe-guarded")
class GuardedRest4ProbeEndpoint {
    @PreAuthorize("hasAuthority('READ_DATA')")
    @GetMapping
    fun probe(): String = "ok"
}
