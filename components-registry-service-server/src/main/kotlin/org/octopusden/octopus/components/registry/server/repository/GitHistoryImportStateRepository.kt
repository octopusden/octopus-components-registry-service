package org.octopusden.octopus.components.registry.server.repository

import org.octopusden.octopus.components.registry.server.entity.GitHistoryImportStateEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

interface GitHistoryImportStateRepository : JpaRepository<GitHistoryImportStateEntity, String> {
    /**
     * Atomic claim used as the 409-gate on POST /migrate-history: INSERT
     * the state row or, on primary-key conflict, do nothing. Returns the
     * number of rows inserted (1 = this caller owns the run, 0 = someone
     * else already claimed it). Prevents the find-then-save race between
     * concurrent operators.
     */
    @Modifying
    @Transactional
    @Query(
        value = """
            INSERT INTO git_history_import_state
                (import_key, target_ref, target_sha, status, updated_at)
            VALUES (:importKey, :targetRef, :targetSha, :status, now())
            ON CONFLICT (import_key) DO NOTHING
        """,
        nativeQuery = true,
    )
    fun tryInsert(
        @Param("importKey") importKey: String,
        @Param("targetRef") targetRef: String,
        @Param("targetSha") targetSha: String,
        @Param("status") status: String,
    ): Int
}
