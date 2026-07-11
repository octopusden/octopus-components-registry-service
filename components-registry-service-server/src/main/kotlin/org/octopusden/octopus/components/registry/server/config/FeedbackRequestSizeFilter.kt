package org.octopusden.octopus.components.registry.server.config

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.octopusden.octopus.components.registry.core.dto.ErrorResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.IOException

/**
 * SYS-062: second-line body-size guard for `POST /rest/api/4/feedback`. The portal
 * gateway is the primary limit (internet-facing); this rejects an oversized feedback
 * body should a client reach CRS directly on the internal network.
 *
 * Enforced in TWO ways so a chunked upload (no `Content-Length`) cannot slip past:
 *  1. If `Content-Length` is present and over the cap → reject 413 immediately,
 *     before reading a byte.
 *  2. Otherwise wrap the body in a counting stream; the moment cumulative bytes
 *     exceed the cap while Jackson reads it, throw [PayloadTooLargeException], which
 *     the ControllerExceptionHandler maps to 413.
 *
 * Scoped to the feedback POST only (other endpoints keep container defaults).
 *
 * Ordered HIGHEST_PRECEDENCE so the size guard deterministically wraps the request body
 * BEFORE any other filter (e.g. the prod PostPutBodyLoggingFilter) can read/cache it —
 * the chunked-body 413 protection must not depend on Spring's implementation-defined
 * ordering of otherwise-unordered filters.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class FeedbackRequestSizeFilter(
    private val properties: FeedbackProperties,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !(HttpMethod.POST.matches(request.method) && request.requestURI.trimEnd('/').endsWith(FEEDBACK_PATH))

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: jakarta.servlet.FilterChain,
    ) {
        val max = properties.maxRequestBytes
        val declared = request.contentLengthLong
        if (declared in 0..Long.MAX_VALUE && declared > max) {
            writePayloadTooLarge(response, max)
            return
        }
        filterChain.doFilter(LimitedRequest(request, max), response)
    }

    private fun writePayloadTooLarge(
        response: HttpServletResponse,
        max: Long,
    ) {
        LOG.warn("Rejected feedback submission: body exceeds {} bytes", max)
        response.status = HttpStatus.PAYLOAD_TOO_LARGE.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        response.writer.write(
            objectMapper.writeValueAsString(ErrorResponse("Feedback payload too large (max $max bytes)")),
        )
    }

    /** Wraps the request so its body stream throws once it reads past [max] bytes. */
    private class LimitedRequest(
        request: HttpServletRequest,
        private val max: Long,
    ) : HttpServletRequestWrapper(request) {
        override fun getInputStream(): ServletInputStream = CountingStream(super.getInputStream(), max)

        override fun getReader(): java.io.BufferedReader =
            getInputStream().bufferedReader(charset(characterEncoding ?: "UTF-8"))
    }

    private class CountingStream(
        private val delegate: ServletInputStream,
        private val max: Long,
    ) : ServletInputStream() {
        private var count = 0L

        private fun tally(read: Int): Int {
            if (read >= 0) {
                count += 1
                if (count > max) throw PayloadTooLargeException("Feedback payload too large (max $max bytes)")
            }
            return read
        }

        override fun read(): Int = tally(delegate.read())

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            val n = delegate.read(b, off, len)
            if (n > 0) {
                count += n
                if (count > max) throw PayloadTooLargeException("Feedback payload too large (max $max bytes)")
            }
            return n
        }

        override fun isFinished(): Boolean = delegate.isFinished

        override fun isReady(): Boolean = delegate.isReady

        override fun setReadListener(readListener: ReadListener?) = delegate.setReadListener(readListener)
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(FeedbackRequestSizeFilter::class.java)
        private const val FEEDBACK_PATH = "/rest/api/4/feedback"
    }
}

/**
 * Thrown when a request body exceeds its configured cap mid-read. Mapped to
 * `413 Payload Too Large` by ControllerExceptionHandler (including when Spring wraps
 * it in an `HttpMessageNotReadableException` during body binding).
 */
class PayloadTooLargeException(
    message: String,
) : IOException(message)
