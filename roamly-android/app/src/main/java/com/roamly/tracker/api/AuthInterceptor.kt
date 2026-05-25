package com.roamly.tracker.api

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Injects "Authorization: Bearer <apiKey>" on every request.
 * The key is read lazily so it always reflects the latest SharedPreferences value.
 */
class AuthInterceptor(private val apiKeyProvider: () -> String) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val apiKey = apiKeyProvider()
        val request = if (apiKey.isNotBlank()) {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $apiKey")
                .build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}
