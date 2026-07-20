package org.octopusden.octopus.components.registry.server.architecture.fixtures

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping

/**
 * Fixture for `ArchitectureFitnessRegressionTest`: an UNGUARDED v4 endpoint whose class name does
 * NOT end in `ControllerV4`, mapped under `rest/api/4`. Proves the security rule scopes v4 by
 * request PATH, not class name — the old name-based selector would have missed this.
 *
 * `@RestController` is intentionally omitted so Spring never registers this test fixture as a live
 * (unguarded) bean during `@SpringBootTest` scans; the rule keys on the mapping annotations + path,
 * both of which are present here. It is only ever imported explicitly by the regression test.
 */
@Suppress("FunctionOnlyReturningConstant") // trivial fixture endpoint; body is irrelevant to the rule
@RequestMapping("rest/api/4/probe")
class Rest4ProbeEndpoint {
    @GetMapping
    fun probe(): String = "ok"
}
