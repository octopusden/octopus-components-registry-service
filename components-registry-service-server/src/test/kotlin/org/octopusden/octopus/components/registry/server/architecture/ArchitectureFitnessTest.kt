package org.octopusden.octopus.components.registry.server.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaMethod
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
 * Editing the description or `.because(...)` of a frozen rule orphans its baseline — every
 * previously-accepted violation then resurfaces as "new" and fails the build. If you must change
 * that text, regenerate the baseline in the same commit (delete the rule's store file and re-run
 * the test to re-record the accepted violations).
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
    @ArchTest
    val v4EndpointsMustBeAuthorized: ArchRule =
        FreezingArchRule.freeze(
            methods()
                .that()
                .areDeclaredInClassesThat()
                .haveSimpleNameEndingWith("ControllerV4")
                .and(isHttpEndpoint)
                .should(beGuardedByPreAuthorize)
                .because("all v4 endpoints must declare an authorization policy (non-functional-spec §5.3)"),
        )

    // --- Rule 3: no legacy Groovy inside the DB-source implementation. ---
    // Forward guard: the future `..db..` package (DB-backed component source) must stay pure
    // Kotlin/Java. Vacuously satisfied until that package exists — kept to lock the intent in.
    @ArchTest
    val dbSourceMustNotDependOnGroovy: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("..db..")
            .should()
            .dependOnClassesThat()
            .haveNameMatching(".*[Gg]roovy.*")
            .because("the DB-source implementation must not depend on legacy Groovy (non-functional-spec §5.3)")
            .allowEmptyShould(true)

    // --- Rule 4 (spec §5.3): no cyclic dependencies between the server's top-level slices. ---
    // DEFERRED. The module currently has one large package cycle spanning most slices
    // (config -> dto -> service -> ...). Freezing it produces a ~586 KB baseline whose exact
    // per-edge text breaks on any import reshuffle, causing false CI failures — it would guard
    // almost nothing while being maximally brittle. Enabling this rule requires an actual
    // package-decoupling effort, tracked as a follow-up. Re-add here once the tangle is broken:
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

        private val isHttpEndpoint =
            object : DescribedPredicate<JavaMethod>("are HTTP endpoint methods") {
                override fun test(method: JavaMethod): Boolean = MAPPING_ANNOTATIONS.any { method.isAnnotatedWith(it) }
            }

        // Presence-only check: it verifies @PreAuthorize is declared, NOT that its SpEL expression
        // is restrictive. A future `@PreAuthorize("permitAll()")` would satisfy this rule while being
        // effectively public. Today every @PreAuthorize uses a real permission check, so this is a
        // known limitation to tighten later, not a current gap.
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
    }
}
