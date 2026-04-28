package org.octopusden.octopus.components.registry.server.service.impl

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.octopusden.octopus.components.registry.server.config.ComponentsRegistryProperties
import org.octopusden.octopus.components.registry.server.service.VcsService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Service
import org.springframework.util.FileSystemUtils
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

@Service
@EnableConfigurationProperties(ComponentsRegistryProperties::class)
@ConditionalOnProperty(
    prefix = "components-registry.vcs",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class GitVcsServiceImpl(
    private val componentsRegistryProperties: ComponentsRegistryProperties,
    private val gitTagResolver: GitTagResolver,
) : VcsService {
    override fun cloneComponentsRegistry(): String {
        deleteTemporaryDir()

        val vcsSettings = componentsRegistryProperties.vcs
        val cloneDir = componentsRegistryProperties.workDir

        log.info("Clone Components Registry from ${vcsSettings.root} to $cloneDir")
        val componentsRegistryCloneDir = File(cloneDir)
        val cloneCommand =
            Git
                .cloneRepository()
                .setURI(vcsSettings.root)
                .setDirectory(componentsRegistryCloneDir)

        vcsSettings.username?.let { usernameValue ->
            val provider =
                UsernamePasswordCredentialsProvider(
                    usernameValue,
                    vcsSettings.password,
                )
            cloneCommand.setCredentialsProvider(provider)
        }

        val git = cloneCommand.call()
        return checkoutToValidatedCommit(git)
    }

    private fun checkoutToValidatedCommit(git: Git): String {
        val target = gitTagResolver.resolve(git, componentsRegistryProperties.vcs.tagVersionPrefix)
        log.info("Checkout to ${target.ref}:${target.sha}")
        git
            .checkout()
            .setName(target.sha)
            .call()
        return target.sha
    }

    @PostConstruct
    @Suppress("UnusedPrivateMember")
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
