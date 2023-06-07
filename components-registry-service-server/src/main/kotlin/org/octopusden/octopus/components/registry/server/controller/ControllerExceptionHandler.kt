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

    companion object {
        val log: Logger = LoggerFactory.getLogger(ControllerExceptionHandler::class.java)
    }
}
