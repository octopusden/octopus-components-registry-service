package org.octopusden.octopus.components.registry.server.config

import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.filter.CommonsRequestLoggingFilter

/**
 * Logs POST/PUT request bodies at DEBUG for compatibility-test capture.
 *
 * **⚠️ Security/privacy**
 * Request bodies and query strings can include credentials, tokens, PII,
 * or other sensitive data. Enable this filter only with:
 *  - a short log-retention window for the capture period,
 *  - restricted RBAC on `oc logs` and the Graylog stream,
 *  - data-handling owner approval before each prod-enable.
 *
 * **Off by default**
 * The filter bean is always registered, but `shouldLog` gates emission on
 * DEBUG for the concrete subclass [PostPutBodyLoggingFilter] — NOT on the
 * parent `org.springframework.web.filter.CommonsRequestLoggingFilter`,
 * because Spring's internal `logger` uses the runtime class name.
 *
 * Toggle on via service-config:
 * ```yaml
 * logging:
 *   level:
 *     org.octopusden.octopus.components.registry.server.config.PostPutBodyLoggingFilter: DEBUG
 * ```
 *
 * **Performance caveat**
 * `setIncludePayload(true)` makes Spring wrap every incoming request in
 * `ContentCachingRequestWrapper`, regardless of whether DEBUG is on. The
 * cached buffer is capped at `maxPayloadLength` (4 KB), so the cost is
 * bounded — but it is NOT zero when DEBUG is off.
 *
 * **Truncation**
 * Bodies longer than 4 KB are silently cut off — Spring does NOT append
 * a `... (truncated)` marker. To detect truncation, compare the logged
 * body length against the request's `Content-Length` header.
 */
@Configuration
class RequestBodyLoggingConfig {
    @Bean
    fun requestBodyLoggingFilter(): PostPutBodyLoggingFilter =
        PostPutBodyLoggingFilter().apply {
            setIncludeClientInfo(false)
            setIncludeQueryString(true)
            setIncludePayload(true)
            setMaxPayloadLength(4000)
            setAfterMessagePrefix("REQ-BODY: ")
            setAfterMessageSuffix("")
        }
}

/**
 * Named subclass so Spring's `logger` (which uses the runtime class name)
 * resolves to a stable, configurable category — see [RequestBodyLoggingConfig].
 */
class PostPutBodyLoggingFilter : CommonsRequestLoggingFilter() {
    override fun shouldLog(request: HttpServletRequest): Boolean =
        (request.method == "POST" || request.method == "PUT") && logger.isDebugEnabled

    /** Suppress the "Before request" line — body is not yet available before
     *  the controller reads it, so the line carries no extra info beyond
     *  what the access log already has. */
    override fun beforeRequest(request: HttpServletRequest, message: String) {
        // intentionally empty
    }
}
