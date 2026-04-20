@file:Suppress("TooManyFunctions")

package org.octopusden.octopus.components.registry.server.service.impl

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.octopusden.octopus.components.registry.server.config.ComponentsRegistryProperties
import org.octopusden.octopus.components.registry.server.dto.v4.HistoryImportResult
import org.octopusden.octopus.components.registry.server.entity.AuditLogEntity
import org.octopusden.octopus.components.registry.server.entity.GitHistoryImportStateEntity
import org.octopusden.octopus.components.registry.server.entity.GitHistoryImportStatus
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.GitHistoryImportStateRepository
import org.octopusden.octopus.components.registry.server.service.GitHistoryImportService
import org.octopusden.octopus.components.registry.server.util.AuditDiff
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.util.FileSystemUtils
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

private const val IMPORT_KEY = "component-history"
private const val HISTORY_SOURCE = "git-history"
private const val PROGRESS_LOG_EVERY = 250
private const val UNKNOWN_NAMES_SAMPLE_SIZE = 20

@Service
class GitHistoryImportServiceImpl(
    private val componentsRegistryProperties: ComponentsRegistryProperties,
    private val gitTagResolver: GitTagResolver,
    private val historyLoaderFactory: HistoryEscrowLoaderFactory,
    private val snapshotSerializer: ComponentHistorySnapshotSerializer,
    private val componentRepository: ComponentRepository,
    private val stateRepository: GitHistoryImportStateRepository,
    private val commitWriter: GitHistoryCommitWriter,
) : GitHistoryImportService {
    @Suppress("TooGenericExceptionCaught")
    // Any failure during clone/walk/parse transitions state to FAILED so the
    // operator sees a clear signal that reset=true is required to re-run.
    override fun importHistory(
        toRef: String?,
        reset: Boolean,
    ): HistoryImportResult {
        preflight(reset)

        val started = Instant.now()
        val historyWorkDir = Paths.get(componentsRegistryProperties.workDir + "-history")
        val groovyPrefix = resolveGroovyPrefix()

        try {
            Git
                .cloneRepository()
                .setURI(componentsRegistryProperties.vcs.root)
                .setDirectory(historyWorkDir.toFile())
                .apply {
                    componentsRegistryProperties.vcs.username?.let { usernameValue ->
                        setCredentialsProvider(
                            UsernamePasswordCredentialsProvider(
                                usernameValue,
                                componentsRegistryProperties.vcs.password,
                            ),
                        )
                    }
                }.call()
                .use { git ->
                    return runImport(git, toRef, historyWorkDir, groovyPrefix, started)
                }
        } catch (e: Exception) {
            log.error("History import failed", e)
            markState(status = GitHistoryImportStatus.FAILED)
            throw e
        } finally {
            cleanup(historyWorkDir)
        }
    }

    private fun preflight(reset: Boolean) {
        if (reset) {
            commitWriter.resetHistoryRowsAndState()
        } else {
            stateRepository.findById(IMPORT_KEY).ifPresent { existing ->
                throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "git history import already ran (status=${existing.status}, " +
                        "target=${existing.targetRef}@${existing.targetSha}); use reset=true to re-run",
                )
            }
        }
    }

    private fun runImport(
        git: Git,
        toRef: String?,
        historyWorkDir: Path,
        groovyPrefix: String,
        started: Instant,
    ): HistoryImportResult {
        val target = resolveTarget(git, toRef)
        log.info("History import target: {}@{}", target.ref, target.sha)

        commitWriter.saveState(
            GitHistoryImportStateEntity(
                importKey = IMPORT_KEY,
                targetRef = target.ref,
                targetSha = target.sha,
                status = GitHistoryImportStatus.IN_PROGRESS.name,
            ),
        )

        val loader = historyLoaderFactory.build(historyWorkDir)
        val repo = git.repository
        val chain =
            RevWalk(repo).use { walk ->
                firstParentChain(walk, ObjectId.fromString(target.sha))
            }
        log.info("First-parent chain size: {}", chain.size)

        val stats = ImportStats()
        var prevSnapshot: Map<String, Map<String, Any?>> = emptyMap()

        RevWalk(repo).use { walk ->
            chain.forEachIndexed { index, commitId ->
                val commit = walk.parseCommit(commitId)
                stats.processed++
                prevSnapshot =
                    processCommit(git, repo, loader, commit, prevSnapshot, groovyPrefix, stats)
                logProgress(index, chain.size, stats.auditRecords)
            }
        }

        flushParseSkipBatch(stats.parseSkipBatch)
        if (stats.unknownNames.isNotEmpty()) {
            log.warn(
                "history import skipped unknown component names: count={}, sample={}",
                stats.unknownNames.size,
                stats.unknownNames.take(UNKNOWN_NAMES_SAMPLE_SIZE),
            )
        }

        markState(status = GitHistoryImportStatus.COMPLETED)

        return HistoryImportResult(
            targetRef = target.ref,
            targetSha = target.sha,
            processedCommits = stats.processed,
            skippedNoGroovy = stats.skippedNoGroovy,
            skippedParseError = stats.skippedParseError,
            skippedUnknownNames = stats.unknownNames.size,
            auditRecords = stats.auditRecords,
            durationMs =
                java.time.Duration
                    .between(started, Instant.now())
                    .toMillis(),
        )
    }

    private fun resolveTarget(
        git: Git,
        toRef: String?,
    ): ResolvedTarget =
        toRef?.let { ref ->
            val sha =
                git.repository.resolve(ref)?.name
                    ?: throw IllegalArgumentException("cannot resolve toRef '$ref' in cloned repository")
            ResolvedTarget(ref = ref, sha = sha)
        } ?: gitTagResolver.resolve(git, componentsRegistryProperties.vcs.tagVersionPrefix)

    private fun processCommit(
        git: Git,
        repo: Repository,
        loader: org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader,
        commit: RevCommit,
        prevSnapshot: Map<String, Map<String, Any?>>,
        groovyPrefix: String,
        stats: ImportStats,
    ): Map<String, Map<String, Any?>> {
        if (prevSnapshot.isNotEmpty() && !touchesGroovy(repo, commit, groovyPrefix)) {
            stats.skippedNoGroovy++
            return prevSnapshot
        }

        checkoutCommit(git, commit)

        val curSnapshot = parseSnapshot(loader, commit, stats) ?: return prevSnapshot

        val rows = buildAuditRowsForCommit(commit, prevSnapshot, curSnapshot, stats.unknownNames)
        commitWriter.persistCommitRows(rows)
        stats.auditRecords += rows.size
        return curSnapshot
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    // The Groovy DSL loader throws a wide range of internal exceptions on historical
    // commits whose DSL shape is no longer understood by the current parser. We record
    // a single aggregated batch at INFO/WARN rather than logging each stack trace.
    private fun parseSnapshot(
        loader: org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader,
        commit: RevCommit,
        stats: ImportStats,
    ): Map<String, Map<String, Any?>>? =
        try {
            loader
                .loadFullConfigurationWithoutValidationForUnknownAttributes(emptyMap())
                .escrowModules
                .mapValues { (_, module) -> snapshotSerializer.serialize(module) }
        } catch (e: Exception) {
            stats.parseSkipBatch += commit.name
            stats.skippedParseError++
            if (stats.parseSkipBatch.size >= PROGRESS_LOG_EVERY) {
                flushParseSkipBatch(stats.parseSkipBatch)
            }
            null
        }

    private fun buildAuditRowsForCommit(
        commit: RevCommit,
        prev: Map<String, Map<String, Any?>>,
        cur: Map<String, Map<String, Any?>>,
        unknownNames: MutableSet<String>,
    ): List<AuditLogEntity> {
        val author = commit.authorIdent
        val changedBy = "${author.name} <${author.emailAddress}>"
        val changedAt = author.`when`.toInstant()

        return (prev.keys + cur.keys).mapNotNull { name ->
            val componentId = componentRepository.findByName(name)?.id
            if (componentId == null) {
                unknownNames += name
                return@mapNotNull null
            }
            val oldValue = prev[name]
            val newValue = cur[name]
            val action = resolveAction(oldValue, newValue) ?: return@mapNotNull null

            AuditLogEntity(
                entityType = "Component",
                entityId = componentId.toString(),
                action = action,
                changedBy = changedBy,
                changedAt = changedAt,
                oldValue = oldValue,
                newValue = newValue,
                changeDiff = AuditDiff.compute(oldValue, newValue),
                correlationId = commit.name,
                source = HISTORY_SOURCE,
            )
        }
    }

    private fun resolveAction(
        oldValue: Map<String, Any?>?,
        newValue: Map<String, Any?>?,
    ): String? =
        when {
            oldValue == null && newValue != null -> "CREATE"
            oldValue != null && newValue == null -> "DELETE"
            oldValue != null && newValue != null && oldValue != newValue -> "UPDATE"
            else -> null
        }

    private fun firstParentChain(
        walk: RevWalk,
        targetSha: ObjectId,
    ): List<ObjectId> {
        val list = mutableListOf<ObjectId>()
        var current: RevCommit? = walk.parseCommit(targetSha)
        while (current != null) {
            list += current.id
            val firstParent = current.parents.firstOrNull() ?: break
            current = walk.parseCommit(firstParent)
        }
        return list.asReversed()
    }

    private fun touchesGroovy(
        repo: Repository,
        commit: RevCommit,
        groovyPrefix: String,
    ): Boolean {
        val parent = commit.parents.firstOrNull() ?: return true
        repo.newObjectReader().use { reader ->
            val parentTree = CanonicalTreeParser().apply { reset(reader, parent.tree) }
            val currentTree = CanonicalTreeParser().apply { reset(reader, commit.tree) }
            val diffs =
                Git(repo)
                    .diff()
                    .setOldTree(parentTree)
                    .setNewTree(currentTree)
                    .call()
            return diffs.any { entry ->
                val candidate =
                    entry.newPath.takeUnless { it == "/dev/null" } ?: entry.oldPath
                candidate.endsWith(".groovy") &&
                    (groovyPrefix.isEmpty() || candidate.startsWith(groovyPrefix))
            }
        }
    }

    private fun checkoutCommit(
        git: Git,
        commit: RevCommit,
    ) {
        git
            .checkout()
            .setName(commit.name)
            .setForced(true)
            .call()
    }

    private fun resolveGroovyPrefix(): String {
        val liveWorkDir = Paths.get(componentsRegistryProperties.workDir).toAbsolutePath().normalize()
        val liveGroovyPath = Paths.get(componentsRegistryProperties.groovyPath).toAbsolutePath().normalize()
        if (!liveGroovyPath.startsWith(liveWorkDir)) return ""
        val relative = liveWorkDir.relativize(liveGroovyPath).toString()
        if (relative.isEmpty()) return ""
        return if (relative.endsWith("/")) relative else "$relative/"
    }

    private fun markState(status: GitHistoryImportStatus) {
        stateRepository.findById(IMPORT_KEY).ifPresent { entity ->
            entity.status = status.name
            commitWriter.saveState(entity)
        }
    }

    private fun flushParseSkipBatch(batch: MutableList<String>) {
        if (batch.isEmpty()) return
        log.warn(
            "history import parse-skip batch: count={}, first={}, last={}",
            batch.size,
            batch.first(),
            batch.last(),
        )
        batch.clear()
    }

    private fun logProgress(
        index: Int,
        total: Int,
        auditRecords: Int,
    ) {
        if ((index + 1) % PROGRESS_LOG_EVERY == 0) {
            log.info("history import progress: {}/{} commits, auditRecords={}", index + 1, total, auditRecords)
        }
    }

    private fun cleanup(historyWorkDir: Path) {
        if (Files.exists(historyWorkDir)) {
            log.info("Cleaning history clone at {}", historyWorkDir)
            FileSystemUtils.deleteRecursively(historyWorkDir.toFile())
        }
    }

    private class ImportStats {
        var processed: Int = 0
        var skippedNoGroovy: Int = 0
        var skippedParseError: Int = 0
        var auditRecords: Int = 0
        val parseSkipBatch: MutableList<String> = mutableListOf()
        val unknownNames: MutableSet<String> = mutableSetOf()
    }

    companion object {
        private val log = LoggerFactory.getLogger(GitHistoryImportServiceImpl::class.java)
    }
}
