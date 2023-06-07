package org.octopusden.octopus.components.registry.api.beans

import org.octopusden.octopus.components.registry.api.distribution.entities.FileDistributionEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.lang.IllegalStateException
import org.octopusden.octopus.components.registry.api.beans.FileDistributionEntityBean

class FileDistributionEntityBeanTest {
    @Test
    fun testFileDistributionEntity() {
        val fileDistributionEntity: FileDistributionEntity = FileDistributionEntityBean("file:///\${env.CONF_PATH}/tscomponent/Core/\${version}/ts-\${version}.zip?artifactId=ts")
        assertEquals("/\${env.CONF_PATH}/tscomponent/Core/\${version}/ts-\${version}.zip", fileDistributionEntity.uri.path)
        assertEquals("ts", fileDistributionEntity.artifactId.orElseThrow { IllegalStateException() })
    }
}