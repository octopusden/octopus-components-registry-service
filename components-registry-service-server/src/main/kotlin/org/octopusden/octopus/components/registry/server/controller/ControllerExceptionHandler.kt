package org.octopusden.octopus.components.registry.server.controller

import org.octopusden.octopus.components.registry.core.dto.ErrorResponse
import org.octopusden.octopus.components.registry.core.exceptions.BaseComponentsRegistryException
import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException
import org.octopusden.octopus.components.registry.core.exceptions.RepositoryNotPreparedException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.HttpEntity
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus

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

    companion object {
        val log: Logger = LoggerFactory.getLogger(ControllerExceptionHandler::class.java)
    }
}
