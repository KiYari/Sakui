package com.eeck.server.core.errors

/** Domain result type: exceptions stay reserved for what's actually exceptional. */
sealed interface Outcome<out T> {
    data class Success<out T>(val value: T) : Outcome<T>
    data class Failure(val error: DomainError) : Outcome<Nothing>
}
