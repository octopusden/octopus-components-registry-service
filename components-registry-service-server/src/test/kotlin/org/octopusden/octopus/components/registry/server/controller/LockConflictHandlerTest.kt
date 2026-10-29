package org.octopusden.octopus.components.registry.server.controller

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.core.dto.ErrorCodes
import org.springframework.dao.CannotAcquireLockException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** SYS-099 §6.8: a deadlock victim (PostgreSQL 40P01 → CannotAcquireLockException) answers 409 LOCK_CONFLICT, not 500. */
class LockConflictHandlerTest {
    @RestController
    class DeadlockController {
        @GetMapping("/deadlock")
        fun deadlock(): ResponseEntity<String> = throw CannotAcquireLockException("deadlock detected")
    }

    private val mvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(DeadlockController())
            .setControllerAdvice(ControllerExceptionHandler())
            .build()

    @Test
    @DisplayName("SYS-099: a lost row-lock race maps to 409 LOCK_CONFLICT")
    fun `SYS-099 lock conflict maps to 409`() {
        mvc
            .perform(get("/deadlock"))
            .andExpect(status().`is`(HttpStatus.CONFLICT.value()))
            .andExpect(jsonPath("$.errorCode").value(ErrorCodes.LOCK_CONFLICT))
    }
}
