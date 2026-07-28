package com.roamly.data.repository

import com.roamly.data.api.RoamlyApi
import com.roamly.data.prefs.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a login attempt. */
sealed class LoginResult {
    object Success : LoginResult()
    /** Server emailed a code (new device / unverified signup); call [AuthRepository.verifyCode]. */
    data class NeedsVerification(val email: String, val purpose: String) : LoginResult()
    /** Account has TOTP 2FA enabled; call [AuthRepository.verifyTotp] with an authenticator or backup code. */
    object NeedsTotp : LoginResult()
    data class Error(val message: String) : LoginResult()
}

@Singleton
class AuthRepository @Inject constructor(
    private val api: RoamlyApi,
    private val prefs: UserPreferences,
    private val okHttpClient: OkHttpClient,
) {
    // Held between a NeedsVerification login and the follow-up verifyCode() call.
    // Kept in memory (not prefs.sessionId) so "logged in" doesn't flip true early.
    private var pendingSessionId: String? = null
    private var pendingBase: String? = null

    suspend fun login(serverUrl: String, username: String, password: String): LoginResult {
        return try {
            val base = serverUrl.trimEnd('/')
            prefs.save(base, "", "", username)  // save URL so interceptors target the right host

            val noRedirect = okHttpClient.newBuilder().followRedirects(false).build()

            val csrfToken = withContext(Dispatchers.IO) {
                val req = Request.Builder().url("$base/login/").get()
                    .header("X-Roamly-Client", "app").build()
                extractCookie(noRedirect.newCall(req).execute(), "csrftoken")
            } ?: return LoginResult.Error("Could not get CSRF token from server")

            val deviceCookie = prefs.deviceCookie.first()
            val loginResponse = withContext(Dispatchers.IO) {
                val body = FormBody.Builder()
                    .add("username", username).add("password", password)
                    .add("csrfmiddlewaretoken", csrfToken).build()
                val cookie = buildString {
                    append("csrftoken=").append(csrfToken)
                    if (!deviceCookie.isNullOrBlank()) append("; roamly_device=").append(deviceCookie)
                }
                val req = Request.Builder().url("$base/login/").post(body)
                    .header("X-Roamly-Client", "app")
                    .header("Cookie", cookie).build()
                noRedirect.newCall(req).execute()
            }

            // Any new device-trust token the server hands back, remember it.
            extractCookie(loginResponse, "roamly_device")?.let { prefs.setDeviceCookie(it) }

            when {
                loginResponse.code == 302 -> {
                    val sessionId = extractCookie(loginResponse, "sessionid")
                        ?: return LoginResult.Error("Invalid username or password")
                    finishLogin(base, username, sessionId)
                    LoginResult.Success
                }
                loginResponse.code == 200 -> {
                    // JSON signal from the app-aware login view.
                    val json = JSONObject(loginResponse.body?.string() ?: "{}")
                    when (json.optString("status")) {
                        "verify" -> {
                            pendingSessionId = extractCookie(loginResponse, "sessionid")
                            pendingBase = base
                            LoginResult.NeedsVerification(
                                email = json.optString("email"),
                                purpose = json.optString("purpose", "login"),
                            )
                        }
                        "totp_required" -> {
                            pendingSessionId = extractCookie(loginResponse, "sessionid")
                            pendingBase = base
                            LoginResult.NeedsTotp
                        }
                        else -> LoginResult.Error(json.optString("message", "Login failed"))
                    }
                }
                else -> LoginResult.Error(errorMessage(loginResponse))
            }
        } catch (e: Exception) {
            LoginResult.Error("${e.javaClass.simpleName}: ${e.message ?: "no message"}")
        }
    }

    /** Submit the emailed code to finish a NeedsVerification login. */
    suspend fun verifyCode(code: String): LoginResult {
        val base = pendingBase ?: return LoginResult.Error("Nothing to verify")
        val pending = pendingSessionId
        return try {
            val noRedirect = okHttpClient.newBuilder().followRedirects(false).build()
            // A fresh CSRF token (double-submit — any matching cookie+field value works).
            val csrfToken = withContext(Dispatchers.IO) {
                val req = Request.Builder().url("$base/login/").get()
                    .header("X-Roamly-Client", "app").build()
                extractCookie(noRedirect.newCall(req).execute(), "csrftoken")
            } ?: return LoginResult.Error("Could not get CSRF token")

            val deviceCookie = prefs.deviceCookie.first()
            val resp = withContext(Dispatchers.IO) {
                val body = FormBody.Builder()
                    .add("code", code).add("csrfmiddlewaretoken", csrfToken).build()
                val cookie = buildString {
                    append("csrftoken=").append(csrfToken)
                    if (!pending.isNullOrBlank()) append("; sessionid=").append(pending)
                    if (!deviceCookie.isNullOrBlank()) append("; roamly_device=").append(deviceCookie)
                }
                val req = Request.Builder().url("$base/verify/").post(body)
                    .header("X-Roamly-Client", "app")
                    .header("Cookie", cookie).build()
                noRedirect.newCall(req).execute()
            }

            extractCookie(resp, "roamly_device")?.let { prefs.setDeviceCookie(it) }

            if (resp.code == 200 && JSONObject(resp.body?.string() ?: "{}").optString("status") == "ok") {
                val sessionId = extractCookie(resp, "sessionid")
                    ?: return LoginResult.Error("Verified, but no session was returned")
                val username = prefs.username.first() ?: ""
                finishLogin(base, username, sessionId)
                pendingSessionId = null; pendingBase = null
                LoginResult.Success
            } else {
                LoginResult.Error(errorMessage(resp))
            }
        } catch (e: Exception) {
            LoginResult.Error("${e.javaClass.simpleName}: ${e.message ?: "no message"}")
        }
    }

    /** Submit an authenticator (or backup) code to finish a NeedsTotp login. */
    suspend fun verifyTotp(code: String): LoginResult {
        val base = pendingBase ?: return LoginResult.Error("Nothing to verify")
        val pending = pendingSessionId
        return try {
            val noRedirect = okHttpClient.newBuilder().followRedirects(false).build()
            val csrfToken = withContext(Dispatchers.IO) {
                val req = Request.Builder().url("$base/login/").get()
                    .header("X-Roamly-Client", "app").build()
                extractCookie(noRedirect.newCall(req).execute(), "csrftoken")
            } ?: return LoginResult.Error("Could not get CSRF token")

            val deviceCookie = prefs.deviceCookie.first()
            val resp = withContext(Dispatchers.IO) {
                val body = FormBody.Builder()
                    .add("code", code).add("csrfmiddlewaretoken", csrfToken).build()
                val cookie = buildString {
                    append("csrftoken=").append(csrfToken)
                    if (!pending.isNullOrBlank()) append("; sessionid=").append(pending)
                    if (!deviceCookie.isNullOrBlank()) append("; roamly_device=").append(deviceCookie)
                }
                val req = Request.Builder().url("$base/totp/").post(body)
                    .header("X-Roamly-Client", "app")
                    .header("Cookie", cookie).build()
                noRedirect.newCall(req).execute()
            }

            extractCookie(resp, "roamly_device")?.let { prefs.setDeviceCookie(it) }

            if (resp.code == 200 && JSONObject(resp.body?.string() ?: "{}").optString("status") == "ok") {
                val sessionId = extractCookie(resp, "sessionid")
                    ?: return LoginResult.Error("Verified, but no session was returned")
                val username = prefs.username.first() ?: ""
                finishLogin(base, username, sessionId)
                pendingSessionId = null; pendingBase = null
                LoginResult.Success
            } else {
                LoginResult.Error(errorMessage(resp))
            }
        } catch (e: Exception) {
            LoginResult.Error("${e.javaClass.simpleName}: ${e.message ?: "no message"}")
        }
    }

    private suspend fun finishLogin(base: String, username: String, sessionId: String) {
        val existingKey = prefs.apiKey.first() ?: ""
        prefs.save(base, sessionId, existingKey, username)
        prefs.setDeviceIdIfUnset(defaultDeviceId())
    }

    private fun extractCookie(resp: Response, name: String): String? =
        resp.headers("Set-Cookie")
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter("$name=")?.substringBefore(";")
            ?.takeIf { it.isNotBlank() }

    private fun errorMessage(resp: Response): String = try {
        val body = resp.body?.string() ?: ""
        if (body.startsWith("{")) JSONObject(body).optString("message", "Login failed (${resp.code})")
        else "Login failed (${resp.code})"
    } catch (e: Exception) { "Login failed (${resp.code})" }

    /**
     * Obtain the account's single durable tracking key, creating it once if absent.
     * Idempotent on the server (`/api/keys/app/`). Saves it locally.
     */
    suspend fun ensureApiKey(): Result<String> {
        return try {
            val resp = api.ensureAppApiKey()
            if (!resp.isSuccessful) return Result.Error("Could not get API key (${resp.code()})")
            val key = resp.body()?.key ?: return Result.Error("API key response was empty")
            prefs.setApiKey(key)
            Result.Success(key)
        } catch (e: Exception) {
            Result.Error("${e.javaClass.simpleName}: ${e.message ?: "no message"}")
        }
    }

    /** A friendly, URL-safe id derived from the phone model, e.g. "pixel-8-pro". */
    private fun defaultDeviceId(): String {
        val raw = "${android.os.Build.MODEL}".trim().ifBlank { "android" }
        val slug = raw.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "android" }
        val suffix = (1000..9999).random()
        return "$slug-$suffix"
    }
}
