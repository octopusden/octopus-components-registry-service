package org.octopusden.octopus.components.registry.server.service.impl

import org.slf4j.LoggerFactory

/**
 * Restores the old `EscrowConfigValidator` person-field rules (audit #1, #3, #4,
 * #7) on the v4 write path, plus the runtime active-employee modernization.
 *
 * Extracted as a pure, framework-free object so the rules can be unit-tested in
 * isolation (no Spring / no DB) with a fake [EmployeeDirectoryService] and a
 * fake `isHidden` predicate. The heavy
 * [ComponentManagementServiceImpl] just supplies final-state values and the
 * triggers; this object owns the contract.
 *
 * Semantics (plan building-block #4):
 *  - **Required / pattern** run UNCONDITIONALLY (no external dependency):
 *    `componentOwner` non-blank on a final state; `releaseManager` /
 *    `securityChampion` required + per-element `^\w+$` ONLY when the
 *    distribution gate is `explicit && external`.
 *  - **Active-employee** runs only when [runActiveCheck] is true (a person
 *    field changed OR the distribution gate flipped) AND the employee-service
 *    bean is present. INACTIVE / UNKNOWN → 400; UNAVAILABLE / DISABLED → allow
 *    + WARN (fail-open).
 *  - A field whose `fieldConfigService.isHidden(...)` is true is SKIPPED
 *    entirely (hidden ⇒ stripped ⇒ cannot be required).
 *
 * All failures use `require(...)` → IllegalArgumentException → 400 (see
 * `ControllerExceptionHandler`). Messages MUST start with the exact field name.
 */
object PersonFieldValidator {
    private val log = LoggerFactory.getLogger(PersonFieldValidator::class.java)

    /** Per-element username pattern (NOT a CSV regex — lists are already split). */
    private val USERNAME_PATTERN = Regex("^\\w+$")

    // Field-config paths (match ComponentManagementServiceImpl's isHidden checks).
    const val OWNER_FIELD = "component.componentOwner"
    const val RELEASE_MANAGER_FIELD = "component.releaseManager"
    const val SECURITY_CHAMPION_FIELD = "component.securityChampion"

    /**
     * @param owner final-state `componentOwner` (post-patch).
     * @param releaseManagers final-state canonical release-manager usernames.
     * @param securityChampions final-state canonical security-champion usernames.
     * @param explicit final-state `distributionExplicit` (null treated as false).
     * @param external final-state `distributionExternal` (null treated as false).
     * @param runActiveCheck whether the active-employee check is triggered (a
     *   person field changed or the gate flipped). Required/pattern ignore it.
     * @param isHidden predicate over the field-config path (hidden ⇒ skip the field).
     * @param directory employee-service wrapper (active-check delegate).
     */
    fun validate(
        owner: String?,
        releaseManagers: List<String>,
        securityChampions: List<String>,
        explicit: Boolean?,
        external: Boolean?,
        runActiveCheck: Boolean,
        isHidden: (String) -> Boolean,
        directory: EmployeeDirectoryService,
    ) {
        val ownerHidden = isHidden(OWNER_FIELD)
        val rmHidden = isHidden(RELEASE_MANAGER_FIELD)
        val scHidden = isHidden(SECURITY_CHAMPION_FIELD)

        // (1) componentOwner: required non-blank on every component (no pattern).
        if (!ownerHidden) {
            require(!owner.isNullOrBlank()) { "componentOwner is required and must not be blank" }
        }

        // (3)/(4) releaseManager / securityChampion: required + per-element ^\w+$,
        // but ONLY under the explicit && external distribution gate.
        val gate = (explicit == true) && (external == true)
        if (gate) {
            if (!rmHidden) requireConditionalList("releaseManager", releaseManagers)
            if (!scHidden) requireConditionalList("securityChampion", securityChampions)
        }

        // (7) active-employee modernization — only when triggered AND a bean is wired.
        if (runActiveCheck) {
            if (!ownerHidden && !owner.isNullOrBlank()) {
                requireActive("componentOwner", owner, directory)
            }
            if (gate) {
                if (!rmHidden) releaseManagers.forEach { requireActive("releaseManager", it, directory) }
                if (!scHidden) securityChampions.forEach { requireActive("securityChampion", it, directory) }
            }
        }
    }

    /**
     * Required (non-empty after canonicalization) + every element must match
     * `^\w+$`. The list is already trim/dedupe-canonicalized by the entity, so
     * an element like `"alice,bob"` is a SINGLE element that fails the pattern
     * (do NOT join-then-CSV-regex).
     */
    private fun requireConditionalList(
        field: String,
        values: List<String>,
    ) {
        require(values.isNotEmpty()) {
            "$field is required when distribution is explicit and external"
        }
        values.forEach { value ->
            require(USERNAME_PATTERN.matches(value)) {
                "$field '$value' is not a valid username (must match ^\\w+\$)"
            }
        }
    }

    /** Hard-fail on INACTIVE/UNKNOWN; fail-open (WARN + allow) on UNAVAILABLE/DISABLED. */
    private fun requireActive(
        field: String,
        username: String,
        directory: EmployeeDirectoryService,
    ) {
        when (directory.isActive(username)) {
            ActiveStatus.ACTIVE -> Unit
            ActiveStatus.INACTIVE ->
                throw IllegalArgumentException("$field '$username' is not an active employee")
            ActiveStatus.UNKNOWN ->
                throw IllegalArgumentException("$field '$username' is not a known employee")
            ActiveStatus.UNAVAILABLE ->
                log.warn(
                    "$field active-employee check for '$username' is UNAVAILABLE " +
                        "(employee-service unreachable) — allowing the write (fail-open)",
                )
            ActiveStatus.DISABLED ->
                log.debug("$field active-employee check skipped for '{}' — employee-service disabled", username)
        }
    }
}
