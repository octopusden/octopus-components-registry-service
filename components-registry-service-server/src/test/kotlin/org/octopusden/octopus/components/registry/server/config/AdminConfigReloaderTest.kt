package org.octopusden.octopus.components.registry.server.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.profile.ComponentProfileCatalog
import org.octopusden.octopus.components.registry.server.profile.designExampleProperties
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AdminConfigReloaderTest {
    @Test
    @DisplayName("Decision 4: a second reload cannot refresh between another reload's refresh and its profile load")
    fun refreshAndLoadSerialized() {
        val betweenRefreshAndLoad = AtomicInteger()
        val maxBetween = AtomicInteger()
        val bothRefreshed = CountDownLatch(2)
        var started = false
        val catalog =
            ComponentProfileCatalog {
                if (started) {
                    // Holds the gap open: an unserialized second reload refreshes now; a serialized one cannot.
                    bothRefreshed.await(200, TimeUnit.MILLISECONDS)
                    betweenRefreshAndLoad.decrementAndGet()
                }
                designExampleProperties()
            }
        started = true
        val reloader =
            AdminConfigReloader(
                refresh = {
                    maxBetween.accumulateAndGet(betweenRefreshAndLoad.incrementAndGet(), ::maxOf)
                    bothRefreshed.countDown()
                    emptySet()
                },
                catalog = catalog,
            )

        val executor = Executors.newFixedThreadPool(2)
        try {
            List(2) { executor.submit(Callable { reloader.reload() }) }.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, maxBetween.get(), "a second reload refreshed between another reload's refresh and its profile load")
    }

    @Test
    @DisplayName("a failed refresh still loads the profiles and returns both outcomes")
    fun refreshFailureKeepsProfileOutcome() {
        val outcome =
            AdminConfigReloader(
                refresh = { throw IllegalStateException("binding failed") },
                catalog = ComponentProfileCatalog { designExampleProperties() },
            ).reload()

        assertEquals("binding failed", outcome.refresh.exceptionOrNull()?.message)
        assertTrue(outcome.profiles.usable)
    }
}
