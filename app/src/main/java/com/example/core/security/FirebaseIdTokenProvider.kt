package com.example.core.security

import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

fun interface FirebaseIdTokenFetcher {
    suspend fun fetchIdToken(forceRefresh: Boolean): String?
}

data class AuthenticatedHttpOutcome(
    val response: Response,
    val tokenRefreshedOnRetry: Boolean
)

object FirebaseIdTokenProvider {
    fun defaultFetcher(): FirebaseIdTokenFetcher = FirebaseIdTokenFetcher { forceRefresh ->
        try {
            Firebase.auth.currentUser?.getIdToken(forceRefresh)?.await()?.token
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Executes an OkHttp request built with `Authorization: Bearer <Firebase ID Token>`:
     * 1. Acquires the current user's token using `getIdToken(false)`.
     * 2. If the backend responds HTTP 401 (expired/rejected JWT), refreshes using `getIdToken(true)`
     *    and retries the request once with the refreshed Bearer token.
     */
    suspend fun executeWithBearerRetry(
        httpClient: OkHttpClient,
        tokenFetcher: FirebaseIdTokenFetcher,
        onMissingToken: () -> Throwable,
        requestFactory: suspend (bearerToken: String) -> Request
    ): AuthenticatedHttpOutcome {
        val initialToken = tokenFetcher.fetchIdToken(false)?.trim().orEmpty()
        if (initialToken.isBlank()) {
            throw onMissingToken()
        }

        val firstResponse = httpClient.newCall(requestFactory(initialToken)).execute()
        if (firstResponse.code != 401) {
            return AuthenticatedHttpOutcome(response = firstResponse, tokenRefreshedOnRetry = false)
        }

        val refreshedToken = tokenFetcher.fetchIdToken(true)?.trim().orEmpty()
        if (refreshedToken.isBlank()) {
            return AuthenticatedHttpOutcome(response = firstResponse, tokenRefreshedOnRetry = false)
        }

        firstResponse.close()
        val retryResponse = httpClient.newCall(requestFactory(refreshedToken)).execute()
        return AuthenticatedHttpOutcome(response = retryResponse, tokenRefreshedOnRetry = true)
    }
}
