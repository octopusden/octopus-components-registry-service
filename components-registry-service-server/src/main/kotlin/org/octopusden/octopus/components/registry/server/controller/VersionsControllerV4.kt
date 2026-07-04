package org.octopusden.octopus.components.registry.server.controller

import io.swagger.v3.oas.annotations.Operation
import org.octopusden.octopus.components.registry.core.dto.DetailedComponentVersion
import org.octopusden.octopus.components.registry.server.dto.v4.VersionPreviewRequest
import org.octopusden.octopus.components.registry.server.service.VersionPreviewService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * SYS-059: live version-format preview.
 *
 * `POST /rest/api/4/versions/preview` renders the six version coordinates of a
 * [DetailedComponentVersion] from **ad-hoc** Jira formats (base + per-range
 * overrides) supplied in the body, driven by an input version — with no
 * persistence and no component lookup. It lets the Portal editor show a live,
 * version-accurate preview of the *unsaved* config instead of the saved-config
 * `detailed-version` call.
 *
 * Not `@ConditionalOnDatabaseEnabled`: rendering only needs the always-present
 * `VersionNames` / formatter beans, so preview works in both git and DB modes.
 *
 * Authorization: gated by the authenticated `rest/api/4` catch-all in
 * [org.octopusden.octopus.components.registry.server.config.WebSecurityConfig].
 * The endpoint touches no persisted data, so it needs no finer method-level
 * permission — any authenticated caller may preview formatting math over its own
 * payload.
 */
@RestController
@RequestMapping("rest/api/4/versions")
class VersionsControllerV4(
    private val versionPreviewService: VersionPreviewService,
) {
    @Operation(
        summary = "Preview version coordinates from ad-hoc formats",
        description = "Renders a DetailedComponentVersion for the given input version from base + per-range " +
            "override formats, without persisting or looking up a component.",
    )
    @PostMapping(
        "preview",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun preview(
        @RequestBody request: VersionPreviewRequest,
    ): DetailedComponentVersion {
        LOG.info("Preview version coordinates for '{}' ({} override(s))", request.version, request.overrides.size)
        return versionPreviewService.preview(request)
    }

    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(VersionsControllerV4::class.java)
    }
}
