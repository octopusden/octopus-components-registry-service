package org.octopusden.octopus.components.registry.server.repository

import org.octopusden.octopus.components.registry.server.entity.GitHistoryImportStateEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface GitHistoryImportStateRepository : JpaRepository<GitHistoryImportStateEntity, String> {
    /**
     * Atomic claim: insert the row only if one with the same `import_key`
     * doesn't already exist. Returns `1` on insert, `0` if the row was
     * already present.
     *
     * Used by `GitHistoryImportServiceImpl.preflight` to race-safely claim
     * a single in-flight import across pods. The native query is
     * PostgreSQL-specific (uses `ON CONFLICT DO NOTHING`); H2 in PG-compat
     * mode supports the same syntax for FT/integration tests.
     *
     * `updated_at` is set to `CURRENT_TIMESTAMP` so the heartbeat liveness
     * check has a starting point.
     */
    @Modifying
    @Query(
        value =
            "INSERT INTO git_history_import_state (import_key, target_ref, target_sha, status, updated_at) " +
                "VALUES (:importKey, :targetRef, :targetSha, :status, CURRENT_TIMESTAMP) " +
                "ON CONFLICT (import_key) DO NOTHING",
        nativeQuery = true,
    )
    fun tryInsert(
        @Param("importKey") importKey: String,
        @Param("targetRef") targetRef: String,
        @Param("targetSha") targetSha: String,
        @Param("status") status: String,
    ): Int
}
