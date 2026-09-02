package org.octopusden.octopus.components.registry.server.config

import org.octopusden.octopus.vcsfacade.client.VcsFacadeClient
import org.octopusden.octopus.vcsfacade.client.common.dto.Branch
import org.octopusden.octopus.vcsfacade.client.common.dto.Commit
import org.octopusden.octopus.vcsfacade.client.common.dto.CommitWithFiles
import org.octopusden.octopus.vcsfacade.client.common.dto.CreatePullRequest
import org.octopusden.octopus.vcsfacade.client.common.dto.CreateTag
import org.octopusden.octopus.vcsfacade.client.common.dto.IndexReport
import org.octopusden.octopus.vcsfacade.client.common.dto.PullRequest
import org.octopusden.octopus.vcsfacade.client.common.dto.Repository
import org.octopusden.octopus.vcsfacade.client.common.dto.SearchIssueInRangesResponse
import org.octopusden.octopus.vcsfacade.client.common.dto.SearchIssuesInRangesRequest
import org.octopusden.octopus.vcsfacade.client.common.dto.SearchSummary
import org.octopusden.octopus.vcsfacade.client.common.dto.Tag
import org.octopusden.octopus.vcsfacade.client.impl.ClassicVcsFacadeClient
import org.octopusden.octopus.vcsfacade.client.impl.VcsFacadeClientParametersProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.Date

/**
 * Provides the `VcsFacadeClient` bean [org.octopusden.octopus.components.registry.server.service.archivereadiness.RepositoryChecker]
 * (and [org.octopusden.octopus.components.registry.server.service.archivereadiness.LivenessProbe])
 * depend on. This was never wired when `RepositoryChecker` was introduced — no
 * `@SpringBootTest` exercised the full context until the archive-readiness controller test was
 * added, which is why the gap went unnoticed: every earlier archive-readiness task was covered
 * only by mock-based unit tests.
 *
 * Mirrors [JiraClientConfig] / `TeamcityClientConfig`'s `ClientParametersProvider` pattern
 * (`VcsFacadeClientParametersProvider` is the vcs-facade client library's equivalent). Unlike
 * the nullable Jira client beans, this one is unconditional/non-nullable — `RepositoryChecker`
 * and `LivenessProbe` both depend on `VcsFacadeClient` directly, not `VcsFacadeClient?` — so a
 * real bean must always exist, even when `archive-readiness.vcs-facade.base-url` is blank
 * (this codebase's "VCS unconfigured" signal — see [ArchiveReadinessProperties] kdoc).
 *
 * What makes a blank base URL safe here is [LazyVcsFacadeClient]: it defers actually building
 * the real Feign-backed [ClassicVcsFacadeClient] until the first method call, exactly like
 * `TcDescendantLookup.lazyClient` / `LivenessProbe.lazyTeamcityClient`'s lazy-client-field
 * pattern. Feign's `HardCodedTarget` rejects a BLANK base URL at construction time (not just at
 * call time) — `Util.emptyToNull` + `checkNotNull` throw on `target(type, "")` — so eagerly
 * constructing `ClassicVcsFacadeClient` in this `@Bean` method (as before) would crash the
 * Spring context at startup for any deployment that leaves VCS unconfigured.
 */
@Configuration
class VcsFacadeClientConfig {
    @Bean
    fun vcsFacadeClient(properties: ArchiveReadinessProperties): VcsFacadeClient = LazyVcsFacadeClient(properties)
}

/**
 * A [VcsFacadeClient] whose real Feign-backed delegate is constructed on first use, not at
 * bean-creation time — see [VcsFacadeClientConfig] kdoc for why this is necessary. All members
 * simply forward to [delegate], which triggers construction of the real client on the first
 * call, whatever it is.
 *
 * `TooManyFunctions` is suppressed deliberately: this class exists only to forward every
 * `VcsFacadeClient` interface member (18 of them) to a lazily-constructed delegate — one-line
 * pass-throughs, not genuine complexity. Splitting it up would not reduce complexity, only
 * scatter it.
 */
@Suppress("TooManyFunctions")
private class LazyVcsFacadeClient(
    private val properties: ArchiveReadinessProperties,
) : VcsFacadeClient {
    private val delegate: VcsFacadeClient by lazy {
        ClassicVcsFacadeClient(
            object : VcsFacadeClientParametersProvider {
                override fun getApiUrl(): String = properties.vcsFacade.baseUrl

                override fun getTimeRetryInMillis(): Int = properties.vcsFacade.timeRetryInMillis
            },
        )
    }

    override fun getRepository(sshUrl: String): Repository = delegate.getRepository(sshUrl)

    override fun getCommits(
        sshUrl: String,
        fromHashOrRef: String?,
        fromDate: Date?,
        toHashOrRef: String,
    ): List<Commit> = delegate.getCommits(sshUrl, fromHashOrRef, fromDate, toHashOrRef)

    override fun getCommitsWithFiles(
        sshUrl: String,
        fromHashOrRef: String?,
        fromDate: Date?,
        toHashOrRef: String,
        commitFilesLimit: Int?,
    ): List<CommitWithFiles> = delegate.getCommitsWithFiles(sshUrl, fromHashOrRef, fromDate, toHashOrRef, commitFilesLimit)

    override fun getCommit(
        sshUrl: String,
        hashOrRef: String,
    ): Commit = delegate.getCommit(sshUrl, hashOrRef)

    override fun getCommitWithFiles(
        sshUrl: String,
        hashOrRef: String,
        commitFilesLimit: Int?,
    ): CommitWithFiles = delegate.getCommitWithFiles(sshUrl, hashOrRef, commitFilesLimit)

    override fun getIssuesFromCommits(
        sshUrl: String,
        fromHashOrRef: String?,
        fromDate: Date?,
        toHashOrRef: String,
    ): List<String> = delegate.getIssuesFromCommits(sshUrl, fromHashOrRef, fromDate, toHashOrRef)

    override fun getTags(
        sshUrl: String,
        names: Set<String>?,
    ): List<Tag> = delegate.getTags(sshUrl, names)

    override fun createTag(
        sshUrl: String,
        createTag: CreateTag,
    ): Tag = delegate.createTag(sshUrl, createTag)

    override fun getTag(
        sshUrl: String,
        name: String,
    ): Tag = delegate.getTag(sshUrl, name)

    override fun deleteTag(
        sshUrl: String,
        name: String,
    ) = delegate.deleteTag(sshUrl, name)

    override fun searchIssuesInRanges(searchRequest: SearchIssuesInRangesRequest): SearchIssueInRangesResponse =
        delegate.searchIssuesInRanges(searchRequest)

    override fun createPullRequest(
        sshUrl: String,
        createPullRequest: CreatePullRequest,
    ): PullRequest = delegate.createPullRequest(sshUrl, createPullRequest)

    override fun findByIssueKeys(issueKeys: Set<String>): SearchSummary = delegate.findByIssueKeys(issueKeys)

    override fun findBranchesByIssueKeys(issueKeys: Set<String>): List<Branch> = delegate.findBranchesByIssueKeys(issueKeys)

    override fun findCommitsByIssueKeys(issueKeys: Set<String>): List<Commit> = delegate.findCommitsByIssueKeys(issueKeys)

    override fun findCommitsWithFilesByIssueKeys(
        issueKeys: Set<String>,
        commitFilesLimit: Int?,
    ): List<CommitWithFiles> = delegate.findCommitsWithFilesByIssueKeys(issueKeys, commitFilesLimit)

    override fun findPullRequestsByIssueKeys(issueKeys: Set<String>): List<PullRequest> = delegate.findPullRequestsByIssueKeys(issueKeys)

    override fun reindexRepository(sshUrl: String) = delegate.reindexRepository(sshUrl)

    override fun indexReport(scanRequired: Boolean?): IndexReport = delegate.indexReport(scanRequired)
}
