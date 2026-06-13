package org.octopusden.octopus.components.registry.cli.client

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

class ExitCodeTest {

    @Test
    fun `404 maps to NOT_FOUND`() {
        assertEquals(ExitCode.NOT_FOUND, ExitCodes.fromThrowable(CrsApiException(404, null, "x")))
    }

    @Test
    fun `401 and 403 map to AUTH_REQUIRED`() {
        assertEquals(ExitCode.AUTH_REQUIRED, ExitCodes.fromThrowable(CrsApiException(401, null, "x")))
        assertEquals(ExitCode.AUTH_REQUIRED, ExitCodes.fromThrowable(CrsApiException(403, null, "x")))
    }

    @Test
    fun `5xx maps to SERVER`() {
        assertEquals(ExitCode.SERVER, ExitCodes.fromThrowable(CrsApiException(500, null, "x")))
        assertEquals(ExitCode.SERVER, ExitCodes.fromThrowable(CrsApiException(503, null, "x")))
    }

    @Test
    fun `IOException maps to SERVER`() {
        assertEquals(ExitCode.SERVER, ExitCodes.fromThrowable(IOException("connection refused")))
    }

    @Test
    fun `config resolution failure maps to USAGE`() {
        assertEquals(ExitCode.USAGE, ExitCodes.fromThrowable(ConfigResolutionException("no url")))
    }

    @Test
    fun `numeric exit codes are pinned`() {
        assertEquals(0, ExitCode.OK.code)
        assertEquals(2, ExitCode.USAGE.code)
        assertEquals(3, ExitCode.NOT_FOUND.code)
        assertEquals(4, ExitCode.AUTH_REQUIRED.code)
        assertEquals(5, ExitCode.SERVER.code)
    }
}
