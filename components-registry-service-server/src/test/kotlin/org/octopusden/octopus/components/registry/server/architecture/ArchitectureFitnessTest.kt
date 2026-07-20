package org.octopusden.octopus.components.registry.server.architecture

import com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo
import com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates.annotatedWith
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.freeze.FreezingArchRule
import jakarta.persistence.Entity
import org.springframework.data.repository.Repository
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.RestController
import org.springframework.stereotype.Repository as RepositoryStereotype

/**
 * STRUCTURAL architecture fitness functions for the server module (`docs/registry/non-functional-spec.md`
 * §5.3). Scope is deliberately narrow: package placement of stereotyped beans, plus one frozen
 * layering rule (controllers must not reach the persistence layer directly). These run in the fast
 * `test` gate — no Spring context, no DB — and complement detekt/ktlint/PMD.
 *
 * What is intentionally NOT here:
 * - **v4 authorization policy** — a runtime/framework concern. Verifying it well needs the actual
 *   Spring request mappings and an explicit per-endpoint policy classification, which belongs in a
 *   Spring-context test, not a static bytecode rule. Tracked in TD-017. (An earlier attempt to do it
 *   in ArchUnit ended up re-implementing Spring's mapping resolution — the wrong altitude.)
 * - **DB-source vs legacy-Groovy boundary** — deferred until a real architectural boundary exists
 *   (TD-019); today the DB read path lives in `service.impl` and intentionally depends on the escrow
 *   model for wire-compatibility, so there is no boundary to guard yet.
 * - **No package cycles** — the module is currently one large cycle; deferred to TD-016, to be
 *   enabled unfrozen after a decoupling effort.
 *
 * The frozen layering rule records its accepted pre-existing violations in `archunit_violation_store/`
 * (committed, analogous to `detekt-baseline.xml`) — a **ratchet**: baselined violations are allowed,
 * NEW ones fail the build. The store is IMMUTABLE in normal runs (`allowStoreUpdate=false` in
 * `archunit.properties`); updating the baseline is a deliberate, reviewed action (see that file).
 */
@AnalyzeClasses(
    packages = [ArchitectureFitnessTest.BASE_PACKAGE],
    importOptions = [ImportOption.DoNotIncludeTests::class],
)
class ArchitectureFitnessTest {
    // --- Layering (frozen ratchet): controllers must not use the persistence layer directly. ---
    // Targets Spring Data repositories (`assignableTo` the `Repository` marker — covers every
    // `JpaRepository`) plus any `@Repository`-stereotyped bean, NOT the `..repository..` package as a
    // whole — so query PROJECTION types that merely live there (e.g. `NameCountRow`) are not flagged.
    @ArchTest
    val controllersMustNotUseRepositoriesDirectly: ArchRule =
        FreezingArchRule.freeze(
            noClasses()
                .that()
                .resideInAPackage("..controller..")
                .should()
                .accessClassesThat(
                    assignableTo(Repository::class.java).or(annotatedWith(RepositoryStereotype::class.java)),
                ).because(
                    "controllers must go through the service layer, not Spring Data repositories directly (non-functional-spec §5.3)",
                ),
        )

    // --- Naming / placement conventions (active — expected to hold across the whole module). ---
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
            .areAnnotatedWith(RepositoryStereotype::class.java)
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

    // --- Deferred (TD-016): no cyclic dependencies between the server's top-level slices. ---
    // The module currently has one large package cycle (config → dto → service → …). Enabling this
    // needs a real package-decoupling effort; re-add here UNFROZEN once the tangle is broken:
    //
    //   @ArchTest
    //   val serverSlicesMustBeFreeOfCycles: ArchRule =
    //       slices().matching("$BASE_PACKAGE.(*)..").should().beFreeOfCycles()

    companion object {
        const val BASE_PACKAGE = "org.octopusden.octopus.components.registry.server"
    }
}
