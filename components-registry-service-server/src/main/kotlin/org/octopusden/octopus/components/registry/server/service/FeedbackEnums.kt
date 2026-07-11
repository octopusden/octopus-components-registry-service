package org.octopusden.octopus.components.registry.server.service

/**
 * SYS-062: the three kinds of a feedback submission. Stored as `name()` in
 * `feedback.type` (CHECK-constrained), surfaced verbatim in the v4 API.
 */
enum class FeedbackType {
    BUG,
    IDEA,
    QUESTION,
}

/**
 * SYS-062: feedback triage lifecycle. New submissions are [NEW]; an admin moves a
 * report through [IN_PROGRESS] to [RESOLVED]. Stored as `name()` in `feedback.status`
 * (CHECK-constrained). Retention prunes only [RESOLVED] rows (see FeedbackProperties).
 */
enum class FeedbackStatus {
    NEW,
    IN_PROGRESS,
    RESOLVED,
}
