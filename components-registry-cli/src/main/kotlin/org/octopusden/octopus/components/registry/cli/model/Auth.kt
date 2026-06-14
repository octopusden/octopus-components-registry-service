package org.octopusden.octopus.components.registry.cli.model

import kotlinx.serialization.Serializable

/**
 * Mirror of v4.json `Role`.
 */
@Serializable
data class Role(
    val name: String,
    val permissions: List<String>,
)

/**
 * Mirror of v4.json `User` — the body returned by GET /auth/me.
 *
 * Note: in the canon `roles` is an array of [Role] objects (not plain strings); `groups` is an
 * array of strings. All three properties are required.
 */
@Serializable
data class User(
    val username: String,
    val groups: List<String>,
    val roles: List<Role>,
)
