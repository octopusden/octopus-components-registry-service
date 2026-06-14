package org.octopusden.octopus.components.registry.cli.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TokenManagerTest {

    private val issuer = "https://kc.example/realms/crs"
    private val clientId = "crsctl-public"

    @Test
    fun `empty store throws AuthRequiredException`() {
        val store = RecordingStore(initial = null)
        val tm = TokenManager(issuer, clientId, store, DeviceFlowClient(QueueExchange(emptyList()), RecordingSleeper()))
        assertFailsWith<AuthRequiredException> { tm.accessToken() }
        assertEquals(listOf("load"), store.calls, "must consult the store and stop, never hit the network")
    }

    @Test
    fun `accessToken refreshes and persists the rotated refresh token`() {
        val store = RecordingStore(initial = "RT1")
        val body = """{"access_token":"AT","refresh_token":"RT2","expires_in":300,"token_type":"Bearer"}"""
        val ex = QueueExchange(listOf(200 to body))
        val tm = TokenManager(issuer, clientId, store, DeviceFlowClient(ex, RecordingSleeper()))

        val at = tm.accessToken()

        assertEquals("AT", at)
        assertEquals("RT2", store.value, "rotated refresh token persisted")
        assertEquals(listOf("load", "save"), store.calls)
    }

    @Test
    fun `accessToken caches the token until near expiry`() {
        val store = RecordingStore(initial = "RT1")
        val body = """{"access_token":"AT","refresh_token":"RT1","expires_in":300,"token_type":"Bearer"}"""
        // Only ONE reply queued: a second refresh would throw on the empty queue.
        val ex = QueueExchange(listOf(200 to body))
        var now = 0L
        val tm = TokenManager(issuer, clientId, store, DeviceFlowClient(ex, RecordingSleeper()), nowMillis = { now })

        val first = tm.accessToken()
        now = 100_000 // well inside the 300s validity (minus 30s skew)
        val second = tm.accessToken()

        assertEquals(first, second)
        assertEquals(1, ex.requests.size, "second call served from cache, no extra refresh")
        // A present refresh token is always persisted (save is idempotent); the cached second call
        // does not refresh, so exactly one load+save pair is expected.
        assertEquals(listOf("load", "save"), store.calls)
    }
}
