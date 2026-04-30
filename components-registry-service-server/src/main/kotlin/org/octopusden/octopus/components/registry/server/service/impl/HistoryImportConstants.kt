package org.octopusden.octopus.components.registry.server.service.impl

import java.time.Duration

/** Primary key used in `git_history_import_state` for the single component-history row. */
internal const val HISTORY_IMPORT_KEY = "component-history"

/**
 * If an IN_PROGRESS row was last touched more than this long ago, it is considered stale
 * (the owning pod almost certainly crashed). The force-reset staleness check uses this.
 *
 * A real import keeps `updated_at` fresh via the per-[HEARTBEAT_INTERVAL] heartbeat from
 * [GitHistoryImportServiceImpl]. 30 minutes is conservative: even a single-commit import
 * that takes 29 minutes would still keep the row fresh.
 */
internal val STALE_IN_PROGRESS_THRESHOLD: Duration = Duration.ofMinutes(30)
