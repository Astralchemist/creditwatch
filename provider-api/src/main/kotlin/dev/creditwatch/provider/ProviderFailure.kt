package dev.creditwatch.provider

import java.time.Duration

/**
 * How every [CloudProvider] reports failure. Adapters translate their transport and payload
 * errors into these before they leave the module, so callers never depend on a specific provider.
 *
 * Cases carry no response text: a rejected request may echo the key or account data, and these
 * messages reach the user interface.
 *
 * The cases are plain objects rather than `data object`s, and rate limiting is a plain class:
 * a generated `toString` would hide the exception message from every log line. Stack traces are
 * disabled because the singletons capture one at class load, which points at the classloader
 * rather than the throw site.
 */
sealed class ProviderFailure(message: String) :
    RuntimeException(message, null, false, false) {

    /** The key was rejected. Retrying with the same key cannot succeed. */
    object Unauthorized : ProviderFailure("The provider rejected the API key")

    /** The caller must wait. [retryAfter] is the provider's own hint when it sent one. */
    class RateLimited(val retryAfter: Duration? = null) :
        ProviderFailure("The provider rate limit was reached")

    /** Unreachable, timed out, or failed server-side. Retrying later may succeed. */
    object Unavailable : ProviderFailure("The provider is unavailable")

    /** Reached and authorized, but the payload could not be read as the documented shape. */
    object InvalidResponse : ProviderFailure("The provider returned an unexpected response")
}
