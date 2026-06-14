package org.octopusden.octopus.components.registry.cli.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DeviceFlowClientTest {

    private val issuer = "https://kc.example/realms/crs"
    private val clientId = "crsctl-public"

    @Test
    fun `requestDeviceAuthorization parses response and posts client_id and scope`() {
        val body = """
            {"device_code":"DC","user_code":"WXYZ-1234","verification_uri":"https://kc.example/device",
             "verification_uri_complete":"https://kc.example/device?user_code=WXYZ-1234",
             "expires_in":600,"interval":5}
        """.trimIndent()
        val ex = QueueExchange(listOf(200 to body))
        val client = DeviceFlowClient(exchange = ex, sleeper = RecordingSleeper())

        val auth = client.requestDeviceAuthorization(issuer, clientId, "openid offline_access")

        assertEquals("DC", auth.deviceCode)
        assertEquals("WXYZ-1234", auth.userCode)
        assertEquals("https://kc.example/device", auth.verificationUri)
        assertEquals("https://kc.example/device?user_code=WXYZ-1234", auth.verificationUriComplete)
        assertEquals(600, auth.expiresIn)
        assertEquals(5, auth.interval)

        val req = ex.requests.single()
        assertTrue(req.uri().toString().endsWith("/protocol/openid-connect/auth/device"))
        val form = ex.bodyOf(0)
        assertTrue(form.contains("client_id=crsctl-public"), form)
        assertTrue(form.contains("scope=openid+offline_access") || form.contains("scope=openid%20offline_access"), form)
    }

    @Test
    fun `pollToken returns token after authorization_pending then success`() {
        val pending = """{"error":"authorization_pending"}"""
        val success = """{"access_token":"AT","refresh_token":"RT","expires_in":300,"token_type":"Bearer"}"""
        val ex = QueueExchange(listOf(400 to pending, 200 to success))
        val sleeper = RecordingSleeper()
        val client = DeviceFlowClient(exchange = ex, sleeper = sleeper)

        val token = client.pollToken(issuer, clientId, "DC", interval = 5, expiresInSeconds = 600)

        assertEquals("AT", token.accessToken)
        assertEquals("RT", token.refreshToken)
        assertEquals(listOf(5000L), sleeper.slept, "one 5s wait between the pending and success polls")
        assertEquals(2, ex.requests.size)
        val form = ex.bodyOf(0)
        assertTrue(form.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"), form)
        assertTrue(form.contains("device_code=DC"), form)
    }

    @Test
    fun `pollToken increases interval by 5s on slow_down`() {
        val slowDown = """{"error":"slow_down"}"""
        val success = """{"access_token":"AT","refresh_token":"RT","expires_in":300,"token_type":"Bearer"}"""
        val ex = QueueExchange(listOf(400 to slowDown, 200 to success))
        val sleeper = RecordingSleeper()
        val client = DeviceFlowClient(exchange = ex, sleeper = sleeper)

        client.pollToken(issuer, clientId, "DC", interval = 5, expiresInSeconds = 600)

        // 5s base + 5s slow_down bump = 10s wait.
        assertEquals(listOf(10000L), sleeper.slept)
    }

    @Test
    fun `pollToken aborts on expired_token`() {
        val ex = QueueExchange(listOf(400 to """{"error":"expired_token"}"""))
        val client = DeviceFlowClient(exchange = ex, sleeper = RecordingSleeper())
        val e = assertFailsWith<DeviceFlowException> {
            client.pollToken(issuer, clientId, "DC", interval = 5, expiresInSeconds = 600)
        }
        assertTrue(e.message!!.contains("expired"), e.message!!)
    }

    @Test
    fun `pollToken aborts on access_denied`() {
        val ex = QueueExchange(listOf(400 to """{"error":"access_denied"}"""))
        val client = DeviceFlowClient(exchange = ex, sleeper = RecordingSleeper())
        val e = assertFailsWith<DeviceFlowException> {
            client.pollToken(issuer, clientId, "DC", interval = 5, expiresInSeconds = 600)
        }
        assertTrue(e.message!!.contains("denied"), e.message!!)
    }

    @Test
    fun `refresh returns rotated refresh token`() {
        val body = """{"access_token":"AT2","refresh_token":"RT2","expires_in":300,"token_type":"Bearer"}"""
        val ex = QueueExchange(listOf(200 to body))
        val client = DeviceFlowClient(exchange = ex, sleeper = RecordingSleeper())

        val token = client.refresh(issuer, clientId, "RT1")

        assertEquals("AT2", token.accessToken)
        assertEquals("RT2", token.refreshToken)
        val form = ex.bodyOf(0)
        assertTrue(form.contains("grant_type=refresh_token"), form)
        assertTrue(form.contains("refresh_token=RT1"), form)
    }

    @Test
    fun `revoke returns true on 2xx and false on non-2xx`() {
        val okEx = QueueExchange(listOf(200 to ""))
        assertTrue(DeviceFlowClient(exchange = okEx, sleeper = RecordingSleeper()).revoke(issuer, clientId, "RT"))
        val req = okEx.requests.single()
        assertTrue(req.uri().toString().endsWith("/protocol/openid-connect/revoke"))
        assertTrue(okEx.bodyOf(0).contains("token_type_hint=refresh_token"))

        val failEx = QueueExchange(listOf(503 to ""))
        assertEquals(false, DeviceFlowClient(exchange = failEx, sleeper = RecordingSleeper()).revoke(issuer, clientId, "RT"))
    }

    @Test
    fun `scopeFor appends offline_access only when offline`() {
        assertEquals("openid", DeviceFlowClient.scopeFor(false))
        assertEquals("openid offline_access", DeviceFlowClient.scopeFor(true))
    }
}
