package org.octopusden.octopus.components.registry.server.architecture.fixtures

import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod

/**
 * A Spring "composed" mapping annotation — meta-annotated with @RequestMapping, exactly like the
 * built-in @GetMapping. Used by the fixture below to prove the security rule detects endpoints via
 * META-annotation, not only the six enumerated mapping types.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@RequestMapping(method = [RequestMethod.GET])
annotation class ComposedV4Get

/**
 * Fixture for `ArchitectureFitnessRegressionTest`: an UNGUARDED v4 endpoint (class path under
 * `rest/api/4`) whose method mapping is a COMPOSED annotation. If endpoint detection looked only at
 * the six standard annotations, this method would be invisible and slip past the rule. See
 * [Rest4ProbeEndpoint] for why `@RestController` is omitted.
 */
@Suppress("FunctionOnlyReturningConstant") // trivial fixture endpoint; body is irrelevant to the rule
@RequestMapping("rest/api/4/composed")
class ComposedMappingProbe {
    @ComposedV4Get
    fun probe(): String = "ok"
}
