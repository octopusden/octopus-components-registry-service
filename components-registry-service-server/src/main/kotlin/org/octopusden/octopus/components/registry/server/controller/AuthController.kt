package org.octopusden.octopus.components.registry.server.controller

import org.octopusden.cloud.commons.security.SecurityService
import org.octopusden.cloud.commons.security.dto.User
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("auth")
class AuthController(
    private val securityService: SecurityService,
) {
    @GetMapping("me")
    fun getUserInfo(): User = securityService.getCurrentUser()
}
