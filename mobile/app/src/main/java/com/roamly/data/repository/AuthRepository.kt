package com.roamly.data.repository

import com.roamly.data.api.RoamlyApi
import com.roamly.data.prefs.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val api: RoamlyApi,
    private val prefs: UserPreferences,
    private val okHttpClient: OkHttpClient,
) {
    suspend fun login(serverUrl: String, username: String, password: String): Result<Unit> {
        return try {
            val base = serverUrl.trimEnd('/')

            // Save URL first so BaseUrlInterceptor rewrites requests correctly
            prefs.save(base, "", "", username)

            // Use a no-redirect client so we can read Set-Cookie from the 302 login response
            val noRedirectClient = okHttpClient.newBuilder().followRedirects(false).build()

            // GET the login page to obtain the CSRF token from Set-Cookie
            val csrfToken = withContext(Dispatchers.IO) {
                val req = Request.Builder().url("$base/login/").get().build()
                val resp = noRedirectClient.newCall(req).execute()
                resp.headers("Set-Cookie")
                    .firstOrNull { it.startsWith("csrftoken=") }
                    ?.substringAfter("csrftoken=")
                    ?.substringBefore(";")
            } ?: return Result.Error("Could not get CSRF token from server")

            // POST login directly (not via Retrofit) so we can:
            //  1. Send Cookie: csrftoken=<value> — Django requires the cookie AND the POST field to match
            //  2. Avoid OkHttp following the 302, which hides the Set-Cookie: sessionid header
            val loginResponse = withContext(Dispatchers.IO) {
                val body = FormBody.Builder()
                    .add("username", username)
                    .add("password", password)
                    .add("csrfmiddlewaretoken", csrfToken)
                    .build()
                val req = Request.Builder()
                    .url("$base/login/")
                    .post(body)
                    .header("Cookie", "csrftoken=$csrfToken")
                    .build()
                noRedirectClient.newCall(req).execute()
            }

            if (loginResponse.code != 302) {
                return Result.Error("Login failed (${loginResponse.code})")
            }

            // Extract sessionid from the 302 Set-Cookie header
            val sessionId = loginResponse.headers("Set-Cookie")
                .firstOrNull { it.startsWith("sessionid=") }
                ?.substringAfter("sessionid=")
                ?.substringBefore(";")
                ?: return Result.Error("Invalid username or password")

            // Reuse an existing API key if one is already saved (so login doesn't
            // create a new key every time — the user can also set their own key in Settings)
            val existingKey = prefs.apiKey.first()
            val apiKey = if (!existingKey.isNullOrBlank()) {
                existingKey
            } else {
                prefs.save(base, sessionId, "", username)
                val keyResponse = api.createApiKey()
                if (!keyResponse.isSuccessful) {
                    return Result.Error("Could not create API key (${keyResponse.code()})")
                }
                keyResponse.body()?.key
                    ?: return Result.Error("API key response was empty")
            }

            prefs.save(base, sessionId, apiKey, username)

            // Give this device a stable id automatically so the uploader works out of
            // the box — without it, cached points pile up locally and never sync.
            prefs.setDeviceIdIfUnset(defaultDeviceId())

            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error("${e.javaClass.simpleName}: ${e.message ?: "no message"}")
        }
    }

    /** A friendly, URL-safe id derived from the phone model, e.g. "pixel-8-pro". */
    private fun defaultDeviceId(): String {
        val raw = "${android.os.Build.MODEL}".trim().ifBlank { "android" }
        val slug = raw.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "android" }
        // A short random suffix avoids collisions when two of the same model share an account.
        val suffix = (1000..9999).random()
        return "$slug-$suffix"
    }
}
