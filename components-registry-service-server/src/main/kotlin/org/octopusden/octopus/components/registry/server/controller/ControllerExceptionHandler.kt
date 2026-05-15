package org.octopusden.octopus.components.registry.server.controller

import org.octopusden.octopus.components.registry.core.dto.ErrorResponse
import org.octopusden.octopus.components.registry.core.exceptions.BaseComponentsRegistryException
import org.octopusden.octopus.components.registry.core.exceptions.ComponentNameConflictException
import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException
import org.octopusden.octopus.components.registry.core.exceptions.RepositoryNotPreparedException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.HttpEntity
import org.springframework.http.HttpStatus
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.client.RestClientException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@ControllerAdvice
class ControllerExceptionHandler {
    @ExceptionHandler(NotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun notFoundExceptionHandler(e: BaseComponentsRegistryException): HttpEntity<ErrorResponse> {
        log.warn(e.localizedMessage)
        return HttpEntity(ErrorResponse(e.localizedMessage))
    }

    @ExceptionHandler(RepositoryNotPreparedException::class)
    @ResponseStatus(HttpStatus.TOO_EARLY)
    fun repositoryIsNotPreparedExceptionHandler(e: RepositoryNotPreparedException): HttpEntity<ErrorResponse> {
        log.warn(e.localizedMessage)
        return HttpEntity(ErrorResponse(e.localizedMessage))
    }

    @ExceptionHandler(IllegalStateException::class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    fun illegalStateExceptionHandler(e: IllegalStateException): HttpEntity<ErrorResponse> {
        log.error(e.localizedMessage)
        return HttpEntity(ErrorResponse(e.localizedMessage))
    }

    @ExceptionHandler(RestClientException::class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    fun restClientExceptionHandler(e: RestClientException): HttpEntity<ErrorResponse> {
        log.error("Upstream HTTP call failed", e)
        return HttpEntity(ErrorResponse("Upstream service call failed — check server logs for details"))
    }

    @ExceptionHandler(
        jakarta.persistence.OptimisticLockException::class,
        org.springframework.orm.ObjectOptimisticLockingFailureException::class,
    )
    @ResponseStatus(HttpStatus.CONFLICT)
    fun optimisticLockExceptionHandler(e: Exception): HttpEntity<ErrorResponse> {
        log.warn("Optimistic lock conflict: {}", e.localizedMessage)
        return HttpEntity(ErrorResponse(e.localizedMessage ?: "Concurrent modification conflict"))
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun dataIntegrityViolationExceptionHandler(e: org.springframework.dao.DataIntegrityViolationException): HttpEntity<ErrorResponse> {
        log.warn("Data integrity violation: {}", e.localizedMessage)
        return HttpEntity(ErrorResponse("Data integrity violation: duplicate or invalid data"))
    }

    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun methodArgumentNotValidExceptionHandler(e: org.springframework.web.bind.MethodArgumentNotValidException): HttpEntity<ErrorResponse> {
        val errors = e.bindingResult.fieldErrors.joinToString(", ") { "${it.field}: ${it.defaultMessage}" }
        log.warn("Validation failed: {}", errors)
        return HttpEntity(ErrorResponse("Validation failed: $errors"))
    }

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun illegalArgumentExceptionHandler(e: IllegalArgumentException): HttpEntity<ErrorResponse> {
        log.warn(e.localizedMessage)
        return HttpEntity(ErrorResponse(e.localizedMessage ?: "Invalid request"))
    }

    @ExceptionHandler(ComponentNameConflictException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun componentNameConflictExceptionHandler(e: ComponentNameConflictException): HttpEntity<ErrorResponse> {
        log.warn(e.localizedMessage)
        return HttpEntity(ErrorResponse(e.localizedMessage))
    }

    /**
     * Schema-v2 transitional: `ImportServiceImpl.migrateComponent` /
     * `migrateAllComponents` / `validateMigration` throw this until MIG-039
     * (the §6 import pipeline) lands. Map to 501 Not Implemented so operators
     * hitting `POST /admin/migrate*` get a clean signal instead of a 500 +
     * stack trace.
     */
    @ExceptionHandler(UnsupportedOperationException::class)
    @ResponseStatus(HttpStatus.NOT_IMPLEMENTED)
    fun unsupportedOperationExceptionHandler(e: UnsupportedOperationException): HttpEntity<ErrorResponse> {
        log.warn("Unsupported operation: {}", e.localizedMessage)
        return HttpEntity(ErrorResponse(e.localizedMessage ?: "Operation not implemented"))
    }

    /**
     * Spring Data throws this when a `Pageable.sort` or query-derived method
     * references a property the entity doesn't declare — e.g. `?sort=name,asc`
     * on `ComponentEntity` (which has `componentKey`, not `name`). Without an
     * explicit mapping this propagated as a 500. The list endpoints translate
     * known API-facing field names to entity properties (see
     * `ComponentManagementServiceImpl.translateSort`), so reaching this handler
     * means the caller asked for a truly unknown property — 400 with the
     * offender name is the right shape.
     */
    @ExceptionHandler(org.springframework.data.mapping.PropertyReferenceException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun propertyReferenceExceptionHandler(
        e: org.springframework.data.mapping.PropertyReferenceException,
    ): HttpEntity<ErrorResponse> {
        log.warn("Unknown property in sort/filter: {}", e.localizedMessage)
        return HttpEntity(ErrorResponse("Unknown sort/filter property: '${e.propertyName}'"))
    }

    /**
     * Malformed JSON request body — Spring's default resolver maps this to 400
     * but with `DefaultErrorAttributes` body shape (`{timestamp,status,error,...}`),
     * which is inconsistent with our `ErrorResponse` envelope. Re-map so clients
     * see the same shape regardless of who detected the bad input.
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun httpMessageNotReadableExceptionHandler(e: HttpMessageNotReadableException): HttpEntity<ErrorResponse> {
        log.warn("Malformed request body: {}", e.localizedMessage)
        return HttpEntity(ErrorResponse("Malformed request body"))
    }

    /**
     * Type-mismatch on `@RequestParam` / `@PathVariable` (e.g. non-UUID `{id}`,
     * `?archived=notabool`). Spring's default returns 400 — we override only to
     * unify the body shape with the rest of the API.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun methodArgumentTypeMismatchExceptionHandler(e: MethodArgumentTypeMismatchException): HttpEntity<ErrorResponse> {
        val type = e.requiredType?.simpleName ?: "expected type"
        log.warn("Type mismatch on '{}': {}", e.name, e.localizedMessage)
        return HttpEntity(ErrorResponse("Parameter '${e.name}' could not be converted to $type"))
    }

    /**
     * Future-proof: Jakarta Validation throws this when a `@Validated` controller
     * sees a `@RequestParam` / `@PathVariable` that violates a constraint. Today
     * no V4 controller uses `@Validated`, so this never fires — but absent a
     * handler, the moment one is added bad input would surface as 500.
     */
    @ExceptionHandler(jakarta.validation.ConstraintViolationException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun constraintViolationExceptionHandler(e: jakarta.validation.ConstraintViolationException): HttpEntity<ErrorResponse> {
        val errors = e.constraintViolations.joinToString(", ") { "${it.propertyPath}: ${it.message}" }
        log.warn("Constraint violation: {}", errors)
        return HttpEntity(ErrorResponse("Validation failed: $errors"))
    }

    // Note: AccessDeniedException and AuthenticationException are intercepted by Spring
    // Security's ExceptionTranslationFilter BEFORE they ever reach @ControllerAdvice.
    // Consistent JSON 401/403 bodies are produced by the AuthenticationEntryPoint /
    // AccessDeniedHandler wired in WebSecurityConfig — see those for the actual envelope.
    // Do not re-add @ExceptionHandler entries for the security exceptions here; they
    // would silently never fire and create a false sense of coverage.

    /**
     * Catch-all for anything not matched by a more specific handler. Without this,
     * uncaught exceptions surface as Boot's whitelabel 500 (`DefaultErrorAttributes`
     * body shape), inconsistent with `ErrorResponse`. We log the full stack at ERROR
     * level for operator triage but return a generic, non-leaking message — exception
     * type names and messages may include schema or library internals the caller
     * shouldn't see. Declared last so Spring's most-specific-handler dispatch leaves
     * the dedicated 4xx handlers above untouched.
     *
     * **Security carve-out:** `AccessDeniedException` (incl. Spring Security 6's
     * `AuthorizationDeniedException`) and `AuthenticationException` are thrown at
     * method-invocation time by `@PreAuthorize` / authentication interceptors and
     * propagate UP through DispatcherServlet — which means they would otherwise be
     * caught here BEFORE Spring Security's `ExceptionTranslationFilter` ever sees
     * them, silently downgrading 401/403 to 500. Re-throw them so the security
     * filter chain produces the canonical envelope (see WebSecurityConfig).
     */
    @ExceptionHandler(Throwable::class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    fun fallbackExceptionHandler(e: Throwable): HttpEntity<ErrorResponse> {
        if (e is org.springframework.security.access.AccessDeniedException ||
            e is org.springframework.security.core.AuthenticationException
        ) {
            throw e
        }
        log.error("Unhandled exception", e)
        return HttpEntity(ErrorResponse("Internal server error"))
    }

    companion object {
        val log: Logger = LoggerFactory.getLogger(ControllerExceptionHandler::class.java)
    }
}
