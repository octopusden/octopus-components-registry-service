package org.octopusden.octopus.components.registry.server.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.octopusden.octopus.components.registry.server.security.PermissionEvaluator
import org.octopusden.octopus.components.registry.server.service.impl.ComponentProfileCatalog
import org.octopusden.octopus.components.registry.server.service.impl.ComponentProfilesException
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class ComponentProfilesConfigTest {
    private val runner =
        ApplicationContextRunner()
            .withUserConfiguration(ComponentProfilesConfig::class.java)
            .withBean(PermissionEvaluator::class.java, { mock(PermissionEvaluator::class.java) })

    private val validProfile =
        arrayOf(
            "components-registry.component-profiles.internal.kind=regular",
            "components-registry.component-profiles.internal.title=Internal",
            "components-registry.component-profiles.internal.description=Internal use only",
            "components-registry.component-profiles.internal.order=10",
            "components-registry.component-profiles.internal.classification.external=false",
            "components-registry.component-profiles.internal.classification.explicit=ask",
        )

    @Test
    @DisplayName("Decision 3: a context without any profile fails to start, saying a regular profile is required")
    fun noProfileFailsStartup() {
        runner.run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure)
                .rootCause()
                .isInstanceOf(ComponentProfilesException::class.java)
                .hasMessageContaining("at least one regular profile is required")
        }
    }

    @Test
    @DisplayName("Decision 3: a context with an invalid regular profile fails to start, naming the problem")
    fun invalidProfileFailsStartup() {
        runner
            .withPropertyValues(*validProfile, "components-registry.component-profiles.internal.order=ten")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).rootCause().hasMessageContaining("internal.order: 'ten' is not a whole number")
            }
    }

    @Test
    @DisplayName("Decision 3: a context with valid profiles and a template entry starts; the template is not offered")
    fun templateEntryStarts() {
        runner
            .withPropertyValues(*validProfile, "components-registry.component-profiles.ww-modpack.kind=template")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(ComponentProfileCatalog::class.java).profiles().map { it.id }).containsExactly("internal")
            }
    }
}
