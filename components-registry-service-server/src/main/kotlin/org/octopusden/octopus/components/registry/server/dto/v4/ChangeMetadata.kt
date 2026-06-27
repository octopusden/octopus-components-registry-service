package org.octopusden.octopus.components.registry.server.dto.v4

/**
 * Bean-Validation pattern for the optional change-metadata Jira task key carried
 * on component create/update requests. A blank/whitespace value is a valid
 * "no key" (normalized to null before persisting); a non-blank value must look
 * like a Jira key — a project key of 2+ chars starting with a letter, a dash,
 * then digits (e.g. `ABC-123`). Shared by ComponentCreateRequest /
 * ComponentUpdateRequest and mirrored client-side in the Portal.
 */
const val JIRA_TASK_KEY_PATTERN = "^\\s*\$|^[A-Z][A-Z0-9]+-\\d+\$"
