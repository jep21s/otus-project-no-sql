package com.example.orderservice.web

import java.util.UUID

/**
 * ThreadLocal контекст текущего запроса. Заполняется [UserIdFilter] из заголовка X-User-Id.
 */
object RequestContext {
    private val userIdHolder = ThreadLocal<UUID?>()

    fun setUserId(id: UUID?) = userIdHolder.set(id)

    fun getUserIdOrNull(): UUID? = userIdHolder.get()

    fun getUserId(): UUID =
        userIdHolder.get() ?: throw IllegalStateException("No userId in RequestContext")

    fun clear() = userIdHolder.remove()
}
