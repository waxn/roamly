package com.roamly.di

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.roamly.data.api.RoamlyApi
import com.roamly.data.prefs.UserPreferences
import com.roamly.tracking.TrackingDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AuthInterceptor

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BaseUrlInterceptor

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SessionGuardInterceptor

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Attaches auth headers on every request:
     * - Cookie: sessionid=<value>  → satisfies @login_required on all data endpoints
     * - Authorization: Bearer <key> → satisfies get_api_key_user() on /api/push/
     *
     * Reads directly from DataStore at intercept-time. OkHttp interceptors run on
     * background threads (never the main thread), so runBlocking is safe here and
     * always returns the most recently saved value.
     */
    @Provides
    @Singleton
    @AuthInterceptor
    fun provideAuthInterceptor(prefs: UserPreferences): Interceptor {
        return Interceptor { chain ->
            val sid = runBlocking { prefs.sessionId.first() }
            val key = runBlocking { prefs.apiKey.first() }
            val req = chain.request().newBuilder().apply {
                if (!sid.isNullOrBlank()) header("Cookie", "sessionid=$sid")
                if (!key.isNullOrBlank()) header("Authorization", "Bearer $key")
            }.build()
            chain.proceed(req)
        }
    }

    /**
     * Rewrites the host/scheme/port of every Retrofit request to match the stored
     * server URL.  Retrofit uses a dummy base URL; this interceptor replaces it at
     * runtime so we don't need to recreate the Singleton when the URL changes.
     */
    @Provides
    @Singleton
    @BaseUrlInterceptor
    fun provideBaseUrlInterceptor(prefs: UserPreferences): Interceptor {
        return Interceptor { chain ->
            val target = runBlocking { prefs.serverUrl.first() }?.trimEnd('/')?.toHttpUrlOrNull()
            if (target == null) {
                chain.proceed(chain.request())
            } else {
                val rewritten = chain.request().url.newBuilder()
                    .scheme(target.scheme)
                    .host(target.host)
                    .port(target.port)
                    .build()
                chain.proceed(chain.request().newBuilder().url(rewritten).build())
            }
        }
    }

    /**
     * Detects an expired/invalid session. `@login_required` API endpoints answer
     * with a 302 to `/login/` when the session cookie is missing/expired and no
     * valid Bearer key is present; OkHttp follows it, so we land on the 200 HTML
     * login page. Gson (lenient) then reads that HTML as a bare string and throws
     * "Expected BEGIN_OBJECT but was STRING at line 1 column 1 path $", which
     * surfaced as a cryptic error on the map. Instead, spot the bounce (an /api/
     * request that ended up on /login/ after a redirect) and clear the stale
     * session so the app returns to the login screen cleanly.
     */
    @Provides
    @Singleton
    @SessionGuardInterceptor
    fun provideSessionGuardInterceptor(prefs: UserPreferences): Interceptor {
        return Interceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            val startedAtApi = request.url.encodedPath.startsWith("/api/")
            val landedAtLogin = response.request.url.encodedPath.trimEnd('/').endsWith("/login")
            if (startedAtApi && landedAtLogin && response.priorResponse != null) {
                runBlocking { prefs.clearSession() }
            }
            response
        }
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @AuthInterceptor authInterceptor: Interceptor,
        @BaseUrlInterceptor baseUrlInterceptor: Interceptor,
        @SessionGuardInterceptor sessionGuardInterceptor: Interceptor
    ): OkHttpClient {
        // BASIC (request line + status only) — BODY serialized every full
        // response (e.g. large location/sync payloads) to a string on each call,
        // which is wasteful churn for no user benefit.
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        return OkHttpClient.Builder()
            .addInterceptor(baseUrlInterceptor)       // rewrite URL first
            .addInterceptor(authInterceptor)          // then add auth header
            .addInterceptor(sessionGuardInterceptor)  // catch expired-session redirects
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideGson(): Gson = GsonBuilder().setLenient().create()

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, gson: Gson): Retrofit {
        // Dummy base URL — BaseUrlInterceptor replaces host/port/scheme at runtime
        return Retrofit.Builder()
            .baseUrl("http://roamly.placeholder/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    @Provides
    @Singleton
    fun provideRoamlyApi(retrofit: Retrofit): RoamlyApi = retrofit.create(RoamlyApi::class.java)

    @Provides
    @Singleton
    fun provideAuthRepository(api: RoamlyApi, prefs: com.roamly.data.prefs.UserPreferences, okHttpClient: OkHttpClient): com.roamly.data.repository.AuthRepository =
        com.roamly.data.repository.AuthRepository(api, prefs, okHttpClient)

    @Provides
    @Singleton
    fun provideLocationRepository(api: RoamlyApi): com.roamly.data.repository.LocationRepository =
        com.roamly.data.repository.LocationRepository(api)

    @Provides
    @Singleton
    fun provideTripRepository(api: RoamlyApi, prefs: com.roamly.data.prefs.UserPreferences): com.roamly.data.repository.TripRepository =
        com.roamly.data.repository.TripRepository(api, prefs)

    @Provides
    @Singleton
    fun provideJournalRepository(api: RoamlyApi): com.roamly.data.repository.JournalRepository =
        com.roamly.data.repository.JournalRepository(api)

    @Provides
    @Singleton
    fun provideUpdateRepository(api: RoamlyApi, @ApplicationContext context: Context): com.roamly.data.repository.UpdateRepository =
        com.roamly.data.repository.UpdateRepository(api, context)

    @Provides
    @Singleton
    fun provideHealthRepository(api: RoamlyApi): com.roamly.data.repository.HealthRepository =
        com.roamly.data.repository.HealthRepository(api)

    @Provides
    @Singleton
    fun provideHealthConnectManager(@ApplicationContext context: Context): com.roamly.health.HealthConnectManager =
        com.roamly.health.HealthConnectManager(context)

    @Provides
    @Singleton
    fun provideTrackingDatabase(@ApplicationContext context: Context): TrackingDatabase =
        TrackingDatabase.getInstance(context)

    @Provides
    @Singleton
    fun provideSyncedLocationDao(db: TrackingDatabase): com.roamly.tracking.SyncedLocationDao =
        db.syncedLocationDao()
}
