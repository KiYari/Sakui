package com.eeck.server.core.errors

import io.ktor.http.HttpStatusCode

/** Domain-level failures that [Outcome.Failure] can carry. StatusPages never sees these directly — routes map them. */
sealed interface DomainError {
    data object SessionNotFound : DomainError
    data object LinkAlreadyDeleted : DomainError
    data object NotLinkOwner : DomainError
}

/** The one place that decides which HTTP status a domain error becomes. */
val DomainError.httpStatus: HttpStatusCode
    get() = when (this) {
        DomainError.SessionNotFound -> HttpStatusCode.NotFound
        DomainError.LinkAlreadyDeleted -> HttpStatusCode.Gone
        DomainError.NotLinkOwner -> HttpStatusCode.Forbidden
    }
