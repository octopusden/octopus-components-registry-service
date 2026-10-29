package org.octopusden.octopus.components.registry.server.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * SYS-062 configuration for user feedback. Bound from `components-registry.feedback.*`.
 * The size/count limits are the authoritative server-side caps enforced in
 * FeedbackService (the DTO's `@Size` is only a coarse pre-parse guard).
 */
@ConfigurationProperties(prefix = "components-registry.feedback")
class FeedbackProperties(
    /** Max screenshots per submission. */
    val maxAttachments: Int = 3,
    /** Max decoded bytes per screenshot (2 MiB). */
    val maxAttachmentBytes: Long = 2L * 1024 * 1024,
    /**
     * Hard ceiling on the raw feedback POST body, enforced by the ingress guard
     * BEFORE Jackson parsing (covers both `Content-Length` and chunked bodies).
     * Sized above the base64 expansion of `maxAttachments * maxAttachmentBytes`
     * (~8 MiB) plus JSON/base64 overhead.
     */
    val maxRequestBytes: Long = 12L * 1024 * 1024,
    /**
     * Retention window in days for RESOLVED reports (pruned by `updated_at`).
     * `<= 0` DISABLES the prune entirely (nothing is deleted).
     */
    val retentionDays: Long = 180,
    /** Cron for the retention prune (default 03:45 UTC daily — off the release/CI path). */
    val pruneCron: String = "0 45 3 * * *",
)
