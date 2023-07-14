package org.octopusden.octopus.components.registry.server.service.impl

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.octopusden.octopus.components.registry.server.config.ComponentsRegistryProperties
import org.octopusden.octopus.components.registry.server.service.VcsService
import org.octopusden.releng.versions.NumericVersionFactory
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Service
import org.springframework.util.FileSystemUtils
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import javax.annotation.PostConstruct
import javax.annotation.PreDestroy

@Service
@EnableConfigurationProperties(ComponentsRegistryProperties::class)
@ConditionalOnProperty(
    prefix = "components-registry.vcs",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true
)
class GitVcsServiceImpl(
    private val componentsRegistryProperties: ComponentsRegistryProperties,
    private val numericVersionFactory: NumericVersionFactory,
) : VcsService {

    override fun cloneComponentsRegistry(): String {
        deleteTemporaryDir()

        val vcsSettings = componentsRegistryProperties.vcs
        val cloneDir = componentsRegistryProperties.workDir

        log.info("Clone Components Registry from ${vcsSettings.root} to $cloneDir")
        val componentsRegistryCloneDir = File(cloneDir)
        val cloneCommand = Git.cloneRepository()
            .setURI(vcsSettings.root)
            .setDirectory(componentsRegistryCloneDir)

        vcsSettings.username?.let { usernameValue ->
            val provider = UsernamePasswordCredentialsProvider(
                usernameValue,
                vcsSettings.password
            )
            cloneCommand.setCredentialsProvider(provider)
        }

        val git = cloneCommand.call()
        return checkoutToValidatedCommit(git)
    }

    private fun checkoutToValidatedCommit(git: Git): String {
        val tagPrefix = componentsRegistryProperties.vcs.tagVersionPrefix

        val (tag, commitId) = git.tagList()
            .call()
            .filter { it.name.startsWith(tagPrefix) }
            .map { ref ->
                numericVersionFactory.create(ref.name) to ref
            }
            .maxByOrNull { (version, _) -> version }
            ?.let { (_, ref) ->
                ref.name to (ref.peeledObjectId?.name ?: ref.objectId.name)
            }
            ?: git.log()
                .setMaxCount(1)
                .call()
                .firstOrNull()
                ?.let { revCommit ->
                    "master" to revCommit.name
                }
            ?: throw IllegalStateException("Components Registry is empty, can not continue")

        log.info("Checkout to $tag:$commitId")
        git.checkout()
            .setName(commitId)
            .call()

        return commitId
    }

    @PostConstruct
    private fun postGitVcsServiceImplConstruct() {
        log.info("Service works in Version Control System mode")
    }

    @PreDestroy
    private fun deleteTemporaryDir() {
        val cloneDir = Paths.get(componentsRegistryProperties.workDir)
        if (Files.exists(cloneDir)) {
            log.info("Cleaning Components Registry directory: $cloneDir")
            FileSystemUtils.deleteRecursively(cloneDir)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(GitVcsServiceImpl::class.java)
    }
}
