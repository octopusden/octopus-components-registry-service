// MaxLineLength suppressed because JpaRepository's overrides have long
// generic-laden signatures (`Example<S>`, `FluentQuery.FetchableFluentQuery<S>`)
// that ktlint can't shorten meaningfully. They are mostly `error("unused")`
// boilerplate; breaking each across multiple lines makes the file harder
// to scan, not easier. Suppression is scoped to this stub file ONLY — the
// test file itself keeps the default 140-char limit so genuine long
// assertion messages get caught.
@file:Suppress("MaxLineLength")

package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.server.entity.GitHistoryImportStateEntity
import org.octopusden.octopus.components.registry.server.repository.GitHistoryImportStateRepository
import java.time.Instant
import java.util.Optional

/**
 * Hand-rolled stub for [GitHistoryImportStateRepository]. Spring Data's
 * JpaRepository declares dozens of methods we don't need; the ones that
 * matter are [findById] / [tryInsert] / [save] / a couple of delete
 * operations. The rest is `error("unused")` so accidental usage in a future
 * test fails loudly rather than silently no-op'ing.
 *
 * Used by `HistoryMigrationJobServiceImplTest` (DB-fallback synthesis tests
 * + clearInMemory) instead of a Mockito mock — Mockito's `any()` returns
 * null, which trips the JVM null-check on Kotlin's non-nullable parameters
 * before the proxy can intercept. This stub also exposes [saveRow] for
 * test setup.
 */
internal class StubGitHistoryImportStateRepository : GitHistoryImportStateRepository {
    private val rows = mutableMapOf<String, GitHistoryImportStateEntity>()

    fun saveRow(entity: GitHistoryImportStateEntity) {
        rows[entity.importKey] = entity
    }

    override fun findById(id: String): Optional<GitHistoryImportStateEntity> = Optional.ofNullable(rows[id])

    override fun tryInsert(
        importKey: String,
        targetRef: String,
        targetSha: String,
        status: String,
    ): Int {
        if (rows.containsKey(importKey)) return 0
        rows[importKey] =
            GitHistoryImportStateEntity(
                importKey = importKey,
                targetRef = targetRef,
                targetSha = targetSha,
                status = status,
                updatedAt = Instant.now(),
            )
        return 1
    }

    override fun existsById(id: String): Boolean = rows.containsKey(id)

    override fun findAll(): MutableList<GitHistoryImportStateEntity> = rows.values.toMutableList()

    override fun count(): Long = rows.size.toLong()

    override fun deleteById(id: String) {
        rows.remove(id)
    }

    override fun delete(entity: GitHistoryImportStateEntity) {
        rows.remove(entity.importKey)
    }

    override fun deleteAllById(ids: MutableIterable<String>) {
        ids.forEach(::deleteById)
    }

    override fun deleteAll(entities: MutableIterable<GitHistoryImportStateEntity>) = entities.forEach(::delete)

    override fun deleteAll() {
        rows.clear()
    }

    override fun flush() = Unit

    // Everything below: not used by the service-under-test. Failing loudly
    // surfaces accidental usage in future tests.
    override fun <S : GitHistoryImportStateEntity?> save(entity: S): S = error("unused")

    override fun <S : GitHistoryImportStateEntity?> saveAll(entities: MutableIterable<S>): MutableList<S> = error("unused")

    override fun findAll(sort: org.springframework.data.domain.Sort): MutableList<GitHistoryImportStateEntity> = error("unused")

    override fun findAll(
        pageable: org.springframework.data.domain.Pageable,
    ): org.springframework.data.domain.Page<GitHistoryImportStateEntity> = error("unused")

    override fun findAllById(ids: MutableIterable<String>): MutableList<GitHistoryImportStateEntity> = error("unused")

    override fun <S : GitHistoryImportStateEntity?> saveAndFlush(entity: S): S = error("unused")

    override fun <S : GitHistoryImportStateEntity?> saveAllAndFlush(entities: MutableIterable<S>): MutableList<S> = error("unused")

    @Suppress("DEPRECATION")
    override fun deleteAllInBatch(entities: MutableIterable<GitHistoryImportStateEntity>) = error("unused")

    override fun deleteAllByIdInBatch(ids: MutableIterable<String>) = error("unused")

    override fun deleteAllInBatch() = error("unused")

    @Suppress("DEPRECATION")
    override fun getOne(id: String): GitHistoryImportStateEntity = error("unused")

    @Suppress("DEPRECATION")
    override fun getById(id: String): GitHistoryImportStateEntity = error("unused")

    override fun getReferenceById(id: String): GitHistoryImportStateEntity = error("unused")

    override fun <S : GitHistoryImportStateEntity?> findAll(example: org.springframework.data.domain.Example<S>): MutableList<S> =
        error("unused")

    override fun <S : GitHistoryImportStateEntity?> findAll(
        example: org.springframework.data.domain.Example<S>,
        sort: org.springframework.data.domain.Sort,
    ): MutableList<S> = error("unused")

    override fun <S : GitHistoryImportStateEntity?> findAll(
        example: org.springframework.data.domain.Example<S>,
        pageable: org.springframework.data.domain.Pageable,
    ): org.springframework.data.domain.Page<S> = error("unused")

    override fun <S : GitHistoryImportStateEntity?> findOne(example: org.springframework.data.domain.Example<S>): Optional<S> =
        error("unused")

    override fun <S : GitHistoryImportStateEntity?> count(example: org.springframework.data.domain.Example<S>): Long = error("unused")

    override fun <S : GitHistoryImportStateEntity?> exists(example: org.springframework.data.domain.Example<S>): Boolean = error("unused")

    override fun <S : GitHistoryImportStateEntity?, R : Any?> findBy(
        example: org.springframework.data.domain.Example<S>,
        queryFunction: java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R>,
    ): R = error("unused")
}
