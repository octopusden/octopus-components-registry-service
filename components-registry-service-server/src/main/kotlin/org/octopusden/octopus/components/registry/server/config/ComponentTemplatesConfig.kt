package org.octopusden.octopus.components.registry.server.config

import org.octopusden.octopus.components.registry.server.repository.LabelRepository
import org.octopusden.octopus.components.registry.server.service.ComponentManagementService
import org.octopusden.octopus.components.registry.server.service.ListValues
import org.octopusden.octopus.components.registry.server.service.impl.EmployeeDirectoryService
import org.octopusden.octopus.components.registry.server.service.impl.ParameterChecker
import org.octopusden.octopus.components.registry.server.service.impl.RegistryListValues
import org.octopusden.octopus.components.registry.server.service.impl.TemplateDryRun
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager

/** Wires the template endpoints' collaborators. Database mode only: the dry run is today's create. */
@ConditionalOnDatabaseEnabled
@Configuration
class ComponentTemplatesConfig {
    @Bean
    fun templateListValues(labelRepository: LabelRepository): ListValues = RegistryListValues(labelRepository::findAllCodesSorted)

    @Bean
    fun parameterChecker(
        lists: ListValues,
        employees: EmployeeDirectoryService,
    ): ParameterChecker = ParameterChecker(lists, employees::isActive)

    @Bean
    fun templateDryRun(
        parameterChecker: ParameterChecker,
        lists: ListValues,
        adminConfig: AdminConfigProperties,
        componentManagementService: ComponentManagementService,
        transactionManager: PlatformTransactionManager,
    ): TemplateDryRun =
        TemplateDryRun(
            parameterChecker,
            lists,
            { adminConfig.componentDefaults.templateDefaults() },
            componentManagementService::createComponent,
            transactionManager,
        )
}
