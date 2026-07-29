package org.octopusden.octopus.components.registry.server.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Pure mapping between [ServiceEventType] and its [ServiceEventCategory]. */
class ServiceEventTypeCategoryTest {
    @Test
    fun `operational types are SYSTEM, onboarding view is USER`() {
        assertEquals(ServiceEventCategory.SYSTEM, ServiceEventType.STARTUP.category)
        assertEquals(ServiceEventCategory.SYSTEM, ServiceEventType.VALIDATION_SWEEP.category)
        assertEquals(ServiceEventCategory.USER, ServiceEventType.ONBOARDING_VIDEO_VIEW.category)
    }

    @Test
    fun `categoryOf parses case-insensitively and falls back to SYSTEM for unknown`() {
        assertEquals(ServiceEventCategory.USER, ServiceEventType.categoryOf("onboarding_video_view"))
        assertEquals(ServiceEventCategory.SYSTEM, ServiceEventType.categoryOf("STARTUP"))
        // Forward-compat: a row written by a newer service reads back as SYSTEM, never leaking
        // into the user-facing view.
        assertEquals(ServiceEventCategory.SYSTEM, ServiceEventType.categoryOf("SOME_FUTURE_TYPE"))
    }

    @Test
    fun `namesOf returns exactly the members of a category`() {
        assertEquals(listOf("ONBOARDING_VIDEO_VIEW"), ServiceEventType.namesOf(ServiceEventCategory.USER))
        assertEquals(
            setOf(
                "STARTUP",
                "MIGRATION_COMPONENTS",
                "MIGRATION_HISTORY",
                "TEAMCITY_RESYNC",
                "TEAMCITY_VALIDATION",
                "VALIDATION_SWEEP",
            ),
            ServiceEventType.namesOf(ServiceEventCategory.SYSTEM).toSet(),
        )
    }
}
