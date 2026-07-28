package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * `errorCode` is a machine-readable discriminator for clients that must react
 * differently to error classes sharing one HTTP status — e.g. the Portal needs
 * to distinguish an optimistic-lock 409 ("reload and re-apply") from a
 * uniqueness-violation 409 ("fix the conflicting value"). Nullable: handlers
 * that have no meaningful class emit plain messages, and older servers omit
 * the field entirely. Known values: `OPTIMISTIC_LOCK`, `UNIQUENESS_VIOLATION`,
 * `DATA_INTEGRITY` — clients must tolerate unknown values.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ErrorResponse(
    @JsonProperty("errorMessage") val errorMessage: String,
    // NON_NULL is wire-compat-bearing: v1-v3 ERROR bodies are byte-diffed against the baseline, and
    // an emitted `"errorCode": null` diverges on every error case. Null = omit the field.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("errorCode") val errorCode: String? = null,
)
