package org.octopusden.octopus.components.registry.server.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.freeze.FreezingArchRule
import jakarta.persistence.Entity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Architecture fitness functions for the server module.
 *
 * Implements the rules sketched in `docs/registry/non-functional-spec.md` §5.3. Rules that
 * are currently violated by pre-existing code are wrapped in [FreezingArchRule]: the accepted
 * violations are recorded in the `archunit_violation_store/` baseline (the ArchUnit analogue of
 * `detekt-baseline.xml`), so the rule blocks *new* violations while the recorded ones are burned
 * down over time. Rules with no baseline file are expected to hold for the whole module.
 *
 * WARNING for frozen rules: the violation store keys each baseline by the rule's full textual
 * description, INCLUDING the `.because(...)` text (see `archunit_violation_store/stored.rules`).
 * Editing the description or `.because(...)` of a frozen rule orphans its stored baseline. Note the
 * store semantics (see `archunit.properties`): `allowStoreCreation=false` only fails loud on a
 * wholesale MISSING store directory — a single renamed rule's NEW key is instead SILENTLY
 * re-recorded against the current violations on the next run, because `allowStoreUpdate=true`. That
 * silent re-record captures ALL current violations under the new key, so it can mask a regression
 * introduced alongside the rename. Therefore, when you change a frozen rule's text you must, in the
 * same commit: (1) delete the orphaned old key line from `stored.rules` and its baseline file, then
 * (2) re-run the test to record the new key, and (3) verify the regenerated baseline lists exactly
 * the violations you intend to accept.
 */
@AnalyzeClasses(
    packages = [ArchitectureFitnessTest.BASE_PACKAGE],
    importOptions = [ImportOption.DoNotIncludeTests::class],
)
class ArchitectureFitnessTest {
    // --- Rule 1: layering — controllers must not reach the persistence layer directly. ---
    @ArchTest
    val controllersMustNotAccessRepositories: ArchRule =
        FreezingArchRule.freeze(
            noClasses()
                .that()
                .resideInAPackage("..controller..")
                .should()
                .accessClassesThat()
                .resideInAPackage("..repository..")
                .because("controllers must go through the service layer (non-functional-spec §5.3)"),
        )

    // --- Rule 2: security — every v4 HTTP endpoint must declare an authorization policy. ---
    // A method is authorized when @PreAuthorize is present on the method itself OR on its
    // declaring controller (class-level guard). Endpoints that are intentionally public today
    // are captured in the baseline; the rule prevents any NEW unguarded v4 endpoint.
    //
    // "v4 endpoint" is identified by REQUEST PATH, not by class name: a method is in scope when it
    // carries a Spring mapping annotation and its effective path (the class-level @RequestMapping
    // base stitched with the method mapping) starts with `rest/api/4`. This does not rely on the
    // `*ControllerV4` naming convention, so a v4 controller named differently — or with no class
    // suffix at all — is still checked. `ArchitectureFitnessRegressionTest` pins this: an unguarded
    // `rest/api/4/probe` endpoint whose class is NOT named `*ControllerV4` is caught.
    @ArchTest
    val v4EndpointsMustBeAuthorized: ArchRule = FreezingArchRule.freeze(v4AuthorizationRule())

    // --- Rule 3: no legacy Groovy inside the DB-source implementation. ---
    // Forward guard: the future `..db..` package (DB-backed component source) must stay pure
    // Kotlin/Java. Vacuously satisfied until that package exists — kept to lock the intent in.
    //
    // Legacy Groovy is denied by SOURCE PACKAGE, not only by a `*Groovy*` class name: the escrow
    // config/model classes are authored in Groovy but compile to ordinary names (e.g.
    // `org.octopusden.octopus.escrow.model.Distribution`) that carry no "Groovy" token, so a
    // name-only match misses them. We forbid the escrow legacy packages and the Groovy runtime,
    // keeping the name heuristic as a backstop. `ArchitectureFitnessRegressionTest` pins this.
    @ArchTest
    val dbSourceMustNotDependOnGroovy: ArchRule = dbNoLegacyGroovyRule()

    // --- Rule 4 (spec §5.3): no cyclic dependencies between the server's top-level slices. ---
    // DEFERRED — see TD-016. The module currently has one large package cycle spanning most slices
    // (config -> dto -> service -> ...). Freezing it produces a ~586 KB baseline whose exact
    // per-edge text breaks on any import reshuffle, causing false CI failures — it would guard
    // almost nothing while being maximally brittle. Enabling this rule requires an actual
    // package-decoupling effort, tracked in TD-016. Re-add here once the tangle is broken:
    //
    //   @ArchTest
    //   val serverSlicesMustBeFreeOfCycles: ArchRule =
    //       slices().matching("$BASE_PACKAGE.(*)..").should().beFreeOfCycles()

    // --- Naming / placement conventions (expected to hold across the whole module). ---
    @ArchTest
    val restControllersResideInControllerPackage: ArchRule =
        classes()
            .that()
            .areAnnotatedWith(RestController::class.java)
            .should()
            .resideInAPackage("..controller..")
            .because("@RestController beans belong in the controller layer")

    @ArchTest
    val repositoriesResideInRepositoryPackage: ArchRule =
        classes()
            .that()
            .areAnnotatedWith(Repository::class.java)
            .should()
            .resideInAPackage("..repository..")
            .because("@Repository beans belong in the persistence layer")

    @ArchTest
    val entitiesResideInEntityPackage: ArchRule =
        classes()
            .that()
            .areAnnotatedWith(Entity::class.java)
            .should()
            .resideInAPackage("..entity..")
            .because("JPA @Entity types belong in the entity package")

    // @Service beans live in the service layer or the cohesive `teamcity` integration package.
    @ArchTest
    val servicesResideInServiceOrTeamcityPackage: ArchRule =
        classes()
            .that()
            .areAnnotatedWith(Service::class.java)
            .should()
            .resideInAnyPackage("..service..", "..teamcity..")
            .because("@Service beans belong in the service layer or the teamcity integration package")

    companion object {
        const val BASE_PACKAGE = "org.octopusden.octopus.components.registry.server"

        // v4 endpoints are scoped by request path, independent of class naming.
        private const val V4_PATH_PREFIX = "rest/api/4"

        // Secondary v4 signal (belt-and-suspenders alongside the path): the declaring controller's
        // class-name suffix. Only ADDS coverage — the path check already catches wrongly-named ones.
        private const val V4_CONTROLLER_SUFFIX = "ControllerV4"

        // The `..db..` source must not reach into the legacy escrow tree or the Groovy runtime.
        // `org.octopusden.octopus.escrow..` is denied as a whole MODULE boundary, not just its
        // Groovy files: that tree is the legacy component-resolver config model (a mix of Groovy
        // and Java) and the DB-backed source should stay decoupled from all of it. If a future
        // `..db..` legitimately needs a non-Groovy escrow Java type, narrow this to the Groovy-only
        // subpackages (`..escrow.model..`, `..escrow.configuration..`) at that point.
        private val LEGACY_GROOVY_PACKAGES = arrayOf(
            "org.octopusden.octopus.escrow..",
            "org.codehaus.groovy..",
            "groovy..",
        )

        // All six mapping annotations are enumerated explicitly (not just @RequestMapping):
        // ArchUnit's isAnnotatedWith checks DIRECT annotation presence, and @GetMapping/@PostMapping/…
        // are meta-annotated with @RequestMapping, so matching on @RequestMapping alone would miss them.
        private val MAPPING_ANNOTATIONS = listOf(
            RequestMapping::class.java,
            GetMapping::class.java,
            PostMapping::class.java,
            PutMapping::class.java,
            DeleteMapping::class.java,
            PatchMapping::class.java,
        )

        // A v4 endpoint = an HTTP-endpoint method whose controller is v4. "HTTP endpoint" is any
        // method (meta-)annotated with @RequestMapping — this covers the six standard mappings AND
        // Spring composed annotations meta-annotated with them. "v4" is decided by EITHER signal, so
        // a controller needs only one to be covered:
        //   - request path: class-level @RequestMapping base stitched with the method mapping starts
        //     at the `rest/api/4` segment (exact or `rest/api/4/…`, not `rest/api/40`); OR
        //   - class name: the declaring controller is `*ControllerV4`.
        // Residual gaps, all only when the class is NOT `*ControllerV4` — a composed annotation
        // carrying the `rest/api/4` path on a custom method- OR class-level mapping annotation (only
        // the six standard mappings' paths are read), and endpoint methods inheriting their
        // class-level @RequestMapping from an abstract base — are tracked in TD-018.
        private val isV4HttpEndpoint =
            object : DescribedPredicate<JavaMethod>(
                "are v4 HTTP endpoints (mapped under $V4_PATH_PREFIX, or declared in a *$V4_CONTROLLER_SUFFIX class)",
            ) {
                override fun test(method: JavaMethod): Boolean {
                    if (!isHttpEndpoint(method)) return false
                    return isMappedUnderV4(method) || method.owner.simpleName.endsWith(V4_CONTROLLER_SUFFIX)
                }
            }

        // Presence-only check: it verifies @PreAuthorize is declared, NOT that its SpEL expression
        // is restrictive. A future `@PreAuthorize("permitAll()")` would satisfy this rule while being
        // effectively public. Today every @PreAuthorize uses a real permission check, so this is a
        // known limitation to tighten later, tracked in TD-017 (not a current gap).
        private val beGuardedByPreAuthorize =
            object : ArchCondition<JavaMethod>("be guarded by @PreAuthorize (on the method or its declaring controller)") {
                override fun check(
                    method: JavaMethod,
                    events: ConditionEvents,
                ) {
                    val guarded = method.isAnnotatedWith(PreAuthorize::class.java) ||
                        method.owner.isAnnotatedWith(PreAuthorize::class.java)
                    if (!guarded) {
                        events.add(
                            SimpleConditionEvent.violated(
                                method,
                                "${method.fullName} is a v4 endpoint without @PreAuthorize on the method or its controller",
                            ),
                        )
                    }
                }
            }

        // Rule 2, exposed unfrozen so ArchitectureFitnessRegressionTest can exercise it against
        // hand-built fixtures. The @ArchTest field wraps this in FreezingArchRule.
        internal fun v4AuthorizationRule(): ArchRule =
            methods()
                .that(isV4HttpEndpoint)
                .should(beGuardedByPreAuthorize)
                .because("all v4 endpoints (mapped under $V4_PATH_PREFIX) must declare an authorization policy (non-functional-spec §5.3)")

        // Rule 3, exposed unfrozen for the same reason. Denies the legacy packages (catches
        // Groovy-authored classes with plain names) plus any `*Groovy*` class as a backstop.
        internal fun dbNoLegacyGroovyRule(): ArchRule =
            noClasses()
                .that()
                .resideInAPackage("..db..")
                .should()
                .dependOnClassesThat(
                    resideInAnyPackage(*LEGACY_GROOVY_PACKAGES).or(nameMatching(".*[Gg]roovy.*")),
                ).because("the DB-source implementation must not depend on legacy Groovy (non-functional-spec §5.3)")
                .allowEmptyShould(true)

        // Any method (meta-)annotated with @RequestMapping — direct @RequestMapping, one of the six
        // standard mappings (each meta-annotated with @RequestMapping), or a composed annotation
        // meta-annotated with any of them.
        private fun isHttpEndpoint(method: JavaMethod): Boolean =
            method.isAnnotatedWith(RequestMapping::class.java) || method.isMetaAnnotatedWith(RequestMapping::class.java)

        // True when the method's effective path (declaring class base + method mapping) is under
        // `rest/api/4`. Composed annotations whose own path is not one of MAPPING_ANNOTATIONS
        // contribute an empty sub-path, so this relies on the class-level base for that case.
        private fun isMappedUnderV4(method: JavaMethod): Boolean {
            val basePaths = classMappingPaths(method.owner)
            val subPaths = MAPPING_ANNOTATIONS
                .mapNotNull { method.tryGetAnnotationOfType(it).orElse(null) }
                .flatMap(::mappingPaths)
                .ifEmpty { listOf("") }
            return basePaths.any { base -> subPaths.any { sub -> isUnderV4(normalizePath("$base/$sub")) } }
        }

        // Reads the DIRECT class-level @RequestMapping of the method's DECLARING class
        // (`method.owner`). Two limitations, both tracked in TD-018 and inactive today (no v4
        // controller triggers them): a composed/meta-annotated class-level mapping is not resolved
        // (only the direct @RequestMapping is read), and an inherited mapping is missed because
        // `owner` is the abstract base, not the concrete subclass that carries the class-level
        // @RequestMapping (the existing v1/v2 `BaseComponentController` idiom).
        private fun classMappingPaths(owner: JavaClass): List<String> =
            owner
                .tryGetAnnotationOfType(RequestMapping::class.java)
                .map { mappingPaths(it) }
                .orElse(listOf(""))

        // Spring mapping annotations expose the path via `value` (aliased to `path`); read both so
        // either attribute style is picked up. An empty mapping contributes a single "" segment.
        private fun mappingPaths(annotation: Annotation): List<String> {
            val paths = when (annotation) {
                is RequestMapping -> annotation.value.toList() + annotation.path.toList()
                is GetMapping -> annotation.value.toList() + annotation.path.toList()
                is PostMapping -> annotation.value.toList() + annotation.path.toList()
                is PutMapping -> annotation.value.toList() + annotation.path.toList()
                is DeleteMapping -> annotation.value.toList() + annotation.path.toList()
                is PatchMapping -> annotation.value.toList() + annotation.path.toList()
                else -> emptyList()
            }
            return paths.distinct().ifEmpty { listOf("") }
        }

        private fun normalizePath(path: String): String = path.removePrefix("/").replace(Regex("/+"), "/")

        // Segment-aware prefix test: the normalized path is exactly `rest/api/4` or a child of it
        // (`rest/api/4/…`). Guards against `rest/api/40`-style false matches.
        private fun isUnderV4(path: String): Boolean = path == V4_PATH_PREFIX || path.startsWith("$V4_PATH_PREFIX/")
    }
}
