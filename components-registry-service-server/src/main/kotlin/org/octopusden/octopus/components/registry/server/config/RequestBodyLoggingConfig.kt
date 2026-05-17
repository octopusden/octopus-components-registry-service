package org.octopusden.octopus.components.registry.server.config

import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.filter.CommonsRequestLoggingFilter

/**
 * Logs POST/PUT request bodies at DEBUG level for compatibility-test capture.
 *
 * Off by default — the filter is registered but only emits when DEBUG is
 * enabled for this class. To turn on at runtime via service-config:
 *
 *     logging:
 *       level:
 *         org.springframework.web.filter.CommonsRequestLoggingFilter: DEBUG
 *
 * Remove that line from service-config and redeploy to stop emitting bodies
 * (no code change required).
 *
 * Only POST and PUT are logged — GET/HEAD/DELETE have no body. The line is
 * emitted AFTER the controller has read the body (Spring's payload caching
 * requires the request to be consumed first).
 *
 * `maxPayloadLength = 4000` is a guard against pathological large bodies;
 * truncated bodies end with `... (truncated)` in the log.
 */
@Configuration
class RequestBodyLoggingConfig {
    @Bean
    fun requestBodyLoggingFilter(): CommonsRequestLoggingFilter =
        object : CommonsRequestLoggingFilter() {
            override fun shouldLog(request: HttpServletRequest): Boolean =
                (request.method == "POST" || request.method == "PUT") && logger.isDebugEnabled

            // Suppress the "Before request" line — body is not yet available
            // before the controller reads it, so the line carries no extra
            // info beyond what the access log already has.
            override fun beforeRequest(request: HttpServletRequest, message: String) {
                // intentionally empty
            }
        }.apply {
            setIncludeClientInfo(false)
            setIncludeQueryString(true)
            setIncludePayload(true)
            setMaxPayloadLength(4000)
            setAfterMessagePrefix("REQ-BODY: ")
            setAfterMessageSuffix("")
        }
}
