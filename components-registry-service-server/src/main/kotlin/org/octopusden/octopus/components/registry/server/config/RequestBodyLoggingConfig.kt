package org.octopusden.octopus.components.registry.server.config

import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.filter.CommonsRequestLoggingFilter

/**
 * Logs POST/PUT request bodies for compatibility-test capture.
 *
 * **⚠️ Security / privacy**
 * Request bodies can include credentials, tokens, PII, or other sensitive
 * data. Enable only with:
 *  - a short log-retention window for the capture period,
 *  - restricted RBAC on `oc logs` and the Graylog stream,
 *  - data-handling owner approval before each prod-enable.
 *
 * **Two-step opt-in** — both must be set to start emitting bodies:
 *  1. `crs.request-body-logging.enabled=true` — also controls bean
 *     registration, so the `ContentCachingRequestWrapper` overhead is only
 *     incurred when this is on.
 *  2. DEBUG on [PostPutBodyLoggingFilter] — gates the actual log line.
 *
 * Requiring both prevents accidental activation via a broad package-level
 * DEBUG override used for unrelated troubleshooting.
 *
 * Service-config toggle:
 * ```yaml
 * crs:
 *   request-body-logging:
 *     enabled: true
 * logging:
 *   level:
 *     org.octopusden.octopus.components.registry.server.config.PostPutBodyLoggingFilter: DEBUG
 * ```
 *
 * **What we do NOT log**
 *  - Query string — already in the Tomcat access log, may contain tokens.
 *  - Headers (Authorization, Cookie, etc.).
 *
 * **Truncation**
 * Bodies past 4 KB are silently cut off — Spring does NOT append a
 * `(truncated)` marker. To detect truncation, compare the logged body
 * length against the request's `Content-Length` header.
 *
 * **Log-line safety**
 * CR/LF characters in the body are escaped to `\r`, `\n` so a pretty-printed
 * JSON body cannot split or forge subsequent log lines.
 */
@Configuration
class RequestBodyLoggingConfig {
    @Bean
    @ConditionalOnProperty(
        name = ["crs.request-body-logging.enabled"],
        havingValue = "true",
        matchIfMissing = false,
    )
    fun requestBodyLoggingFilter(): PostPutBodyLoggingFilter =
        PostPutBodyLoggingFilter().apply {
            setIncludeClientInfo(false)
            setIncludeQueryString(false)
            setIncludePayload(true)
            setMaxPayloadLength(MAX_PAYLOAD_BYTES)
            setAfterMessagePrefix("REQ-BODY: ")
            setAfterMessageSuffix("")
        }

    companion object {
        const val MAX_PAYLOAD_BYTES = 4000
    }
}

/**
 * Named subclass so Spring's `logger` (which uses the runtime class name)
 * resolves to a stable, configurable category — see [RequestBodyLoggingConfig].
 */
class PostPutBodyLoggingFilter : CommonsRequestLoggingFilter() {
    override fun shouldLog(request: HttpServletRequest): Boolean =
        (request.method == "POST" || request.method == "PUT") && logger.isDebugEnabled

    /** Suppress the "Before request" line — body is not yet available
     *  before the controller reads it, so the line carries no extra
     *  info beyond what the access log already has. */
    override fun beforeRequest(
        request: HttpServletRequest,
        message: String,
    ) {
        // intentionally empty
    }

    /** Escape CR/LF so a multi-line JSON body stays on a single log line
     *  and cannot inject or forge log entries. */
    override fun getMessagePayload(request: HttpServletRequest): String? =
        super
            .getMessagePayload(request)
            ?.replace("\r", "\\r")
            ?.replace("\n", "\\n")
}
