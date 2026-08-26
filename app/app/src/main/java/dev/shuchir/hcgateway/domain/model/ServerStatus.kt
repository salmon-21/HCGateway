package dev.shuchir.hcgateway.domain.model

/**
 * Outcome of the connection check. Reachability and authentication are distinct:
 * the API answers 403 for an expired or unknown session, which is a working
 * server, not an unreachable one — conflating the two sent users chasing the
 * network when they only needed to sign in again.
 */
sealed class ServerStatus {
    /** Check in flight. */
    data object Checking : ServerStatus()

    /** /health answered; the session is usable. */
    data object Connected : ServerStatus()

    /** /health answered but the session is gone — re-login required. */
    data object Unauthenticated : ServerStatus()

    /** /health never answered. */
    data object Unreachable : ServerStatus()
}
