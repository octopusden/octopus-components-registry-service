package org.octopusden.octopus.components.registry.server.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/**
 * Single-thread executor for MigrationJobService.
 *
 * One worker is enough: the contract is "only one migration at a time", and the
 * blocking queue is bounded to 1 so a queued-up second submission is impossible
 * (the AtomicReference CAS in the service catches double-submits before this point;
 * the tiny queue is belt-and-braces). `setWaitForTasksToCompleteOnShutdown=true`
 * with a generous `awaitTerminationSeconds` lets a long migration finish gracefully
 * during a rolling restart instead of leaving the DB half-written.
 *
 * `@ConditionalOnMissingBean` allows tests to swap in a [SyncTaskExecutor] (see
 * MigrateEndpointTest) without tripping Spring Boot's bean-definition-override
 * guard. With the test bean registered first via @TestConfiguration / @Import,
 * Spring sees the slot already filled and skips this method.
 */
@Configuration
open class MigrationExecutorConfig {
    @Bean("migrationExecutor")
    @ConditionalOnMissingBean(name = ["migrationExecutor"])
    open fun migrationExecutor(): TaskExecutor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = 1
            maxPoolSize = 1
            queueCapacity = 1
            setThreadNamePrefix("migration-")
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(600)
            initialize()
        }
}
