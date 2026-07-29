package org.octopusden.octopus.validation.teamcity.model

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.octopusden.octopus.validation.dto.teamcity.JavaVersion
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JavaVersionTest {
    @ParameterizedTest
    @ValueSource(strings = ["1.8", "8"])
    @DisplayName("SYS-080: JavaVersion.isEight accepts both the '1.8' and '8' spellings")
    fun `SYS-080 isEight true for 1_8 and 8`(raw: String) {
        assertTrue(JavaVersion(raw).isEight)
    }

    @ParameterizedTest
    @ValueSource(strings = ["11", "17", "21", "1.7", "80", "1.8.0_392"])
    @DisplayName("SYS-080: JavaVersion.isEight is false for anything else, including a full 1.8 build string")
    fun `SYS-080 isEight false for other versions`(raw: String) {
        assertFalse(JavaVersion(raw).isEight)
    }
}
