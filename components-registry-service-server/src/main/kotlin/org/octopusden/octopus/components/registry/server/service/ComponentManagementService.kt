package org.octopusden.octopus.components.registry.server.service

import org.octopusden.octopus.components.registry.server.dto.v4.ComponentCreateRequest
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentDetailResponse
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentEditorsResponse
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentFilter
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentSummaryResponse
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentUpdateRequest
import org.octopusden.octopus.components.registry.server.dto.v4.CompositeOverrideSplitResult
import org.octopusden.octopus.components.registry.server.dto.v4.FieldOverrideCreateRequest
import org.octopusden.octopus.components.registry.server.dto.v4.FieldOverrideResponse
import org.octopusden.octopus.components.registry.server.dto.v4.FieldOverrideUpdateRequest
import org.octopusden.octopus.components.registry.server.dto.v4.SupportedVersionsRequest
import org.octopusden.octopus.components.registry.server.dto.v4.SupportedVersionsResponse
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.util.UUID

@Suppress("TooManyFunctions")
interface ComponentManagementService {
    fun createComponent(request: ComponentCreateRequest): ComponentDetailResponse

    fun getComponent(id: UUID): ComponentDetailResponse

    fun getComponentByName(name: String): ComponentDetailResponse

    fun updateComponent(
        id: UUID,
        request: ComponentUpdateRequest,
    ): ComponentDetailResponse

    fun deleteComponent(id: UUID)

    fun listComponents(
        filter: ComponentFilter,
        pageable: Pageable,
    ): Page<ComponentSummaryResponse>

    fun createFieldOverride(
        componentId: UUID,
        request: FieldOverrideCreateRequest,
    ): FieldOverrideResponse

    fun updateFieldOverride(
        componentId: UUID,
        overrideId: UUID,
        request: FieldOverrideUpdateRequest,
    ): FieldOverrideResponse

    fun deleteFieldOverride(
        componentId: UUID,
        overrideId: UUID,
    )

    fun listFieldOverrides(componentId: UUID): List<FieldOverrideResponse>

    /** Read the component's supported versions (coverage layer — ADR-018). */
    fun getSupportedVersions(componentId: UUID): SupportedVersionsResponse

    /**
     * Declaratively replace the component's supported versions (coverage), re-aligning existing
     * per-attribute overrides to the new breakpoints. Returns the resulting coverage plus any
     * non-blocking warnings (e.g. an override left outside supported).
     */
    fun setSupportedVersions(
        componentId: UUID,
        request: SupportedVersionsRequest,
    ): SupportedVersionsResponse

    /**
     * Render the component (resolved by UUID or name) as a Groovy-style "as-code"
     * definition with ALL version ranges. Carries the canonical component key so
     * the controller can build the download filename without a second lookup.
     */
    fun renderComponentAsCode(idOrName: String): RenderedComponentCode

    /**
     * Render the component flattened/merged for a single concrete [version].
     * Throws `NotFoundException` when the component has no resolvable
     * configuration for the version (e.g. no BASE row, or unparseable version).
     */
    fun renderResolvedComponentAsCode(
        idOrName: String,
        version: String,
    ): RenderedComponentCode

    /**
     * The component's editors (componentOwner + ordered releaseManagers + securityChampions +
     * the owner's manager, SYS-063) for the Portal's read-only "who can edit" surface.
     * Informational only — see [ComponentEditorsResponse].
     */
    fun getEditors(idOrName: String): ComponentEditorsResponse

    /**
     * SYS-066 — one-off split of legacy composite field-override rows (multi-segment Maven ranges
     * from the old DSL import) into single-segment rows, so their ranges become API-editable while
     * the resolved per-version output is preserved. Fail-closed: a malformed range, a composite on
     * any attribute other than `vcs.settings`, self-overlapping segments, or a sibling collision
     * whose payload is not provably identical aborts the WHOLE transaction (no writes).
     *
     * [dryRun] = true previews the change and returns a deterministic [CompositeOverrideSplitResult.manifestToken];
     * a write ([dryRun] = false) must pass that token back (from a preceding dry-run), which is
     * recomputed in-transaction and compared — a mismatch (the data moved since review) or a
     * missing token aborts. Idempotent: with no composites left the manifest is empty.
     */
    fun splitCompositeFieldOverrides(
        dryRun: Boolean,
        manifestToken: String?,
    ): CompositeOverrideSplitResult
}

/** A rendered as-code document plus the canonical component key (for the filename). */
data class RenderedComponentCode(
    val componentKey: String,
    val body: String,
)
