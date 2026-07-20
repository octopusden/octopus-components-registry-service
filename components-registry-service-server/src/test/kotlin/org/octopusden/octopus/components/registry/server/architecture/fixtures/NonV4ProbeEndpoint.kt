package org.octopusden.octopus.components.registry.server.architecture.fixtures

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping

/**
 * Fixture for `ArchitectureFitnessRegressionTest`: an unguarded endpoint OUTSIDE `rest/api/4`.
 * The v4 security rule must NOT flag it — v1/v2/v3 endpoints are out of scope. Guards against the
 * path predicate over-matching. See [Rest4ProbeEndpoint] for why `@RestController` is omitted.
 */
@Suppress("FunctionOnlyReturningConstant") // trivial fixture endpoint; body is irrelevant to the rule
@RequestMapping("rest/api/3/probe")
class NonV4ProbeEndpoint {
    @GetMapping
    fun probe(): String = "ok"
}
