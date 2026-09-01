package org.octopusden.octopus.components.registry.server.dto.v4

/**
 * Response shape for GET rest/api/4/components/{idOrName}/archive-readiness.
 *
 * `ready` is CRS's verdict — callers gate on it rather than deriving one from entries,
 * so an outcome value a caller does not recognise can never unblock archiving.
 * `ready` is false iff at least one entry has outcome FAILED or UNKNOWN.
 */
data class ArchiveReadinessResponse(
    val ready: Boolean,
    val entries: List<ArchiveReadinessEntry>,
)

/**
 * Per-target result in an [ArchiveReadinessResponse].
 *
 * @param targetKind  which external system this entry belongs to
 * @param targetId    stable identity: project key[:version prefix] for JIRA_ISSUES,
 *                    project key for JIRA_PROJECT, TC project id for TEAMCITY_PROJECT,
 *                    repository URL for REPOSITORY
 * @param targetUrl   deep link into the external system, or null when none is available
 * @param outcome     PASSED / FAILED / UNKNOWN
 * @param reason      human-readable reason for FAILED or UNKNOWN; null on PASSED
 * @param sharedWith  live components that share this target (always empty for JIRA_ISSUES)
 * @param openIssues  open issues in scope; non-empty only on JIRA_ISSUES FAILED entries
 */
data class ArchiveReadinessEntry(
    val targetKind: TargetKind,
    val targetId: String,
    val targetUrl: String?,
    val outcome: Outcome,
    val reason: String?,
    val sharedWith: List<String>,
    val openIssues: List<JiraIssueRef>,
)

/** An open Jira issue returned inside a JIRA_ISSUES entry. */
data class JiraIssueRef(
    val key: String,
    val summary: String,
)

/** The kind of external system a single [ArchiveReadinessEntry] addresses. */
enum class TargetKind {
    JIRA_ISSUES,
    JIRA_PROJECT,
    TEAMCITY_PROJECT,
    REPOSITORY,
}

/** The three possible outcomes for a single [ArchiveReadinessEntry]. */
enum class Outcome {
    /** The target is in an acceptable end state (archived, retired, or safely shared). */
    PASSED,

    /** The target needs work before the component can be archived. */
    FAILED,

    /**
     * The state of this target could not be determined — the system was unreachable,
     * the credential was insufficient, or the URL did not resolve to a known provider.
     * UNKNOWN blocks archiving (fails closed), because absent evidence is not evidence
     * of absence.
     */
    UNKNOWN,
}
