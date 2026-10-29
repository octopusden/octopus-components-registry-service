package org.octopusden.octopus.components.registry.server.service.impl

import org.springframework.beans.factory.ObjectProvider

/**
 * Test-only [ObjectProvider] that resolves to nothing — models the "no
 * employee-service client bean wired" (flag off / blank URL) case for
 * [EmployeeDirectoryService] unit tests.
 */
class EmptyObjectProvider<T> : ObjectProvider<T> {
    override fun getObject(): T = throw IllegalStateException("no object available")

    override fun getObject(vararg args: Any?): T = throw IllegalStateException("no object available")

    override fun getIfAvailable(): T? = null

    override fun getIfUnique(): T? = null
}

/**
 * Test-only single-element [ObjectProvider] — models a wired client bean.
 */
class SingletonObjectProvider<T>(
    private val value: T,
) : ObjectProvider<T> {
    override fun getObject(): T = value

    override fun getObject(vararg args: Any?): T = value

    override fun getIfAvailable(): T? = value

    override fun getIfUnique(): T? = value
}
