package com.roamly.di

import com.google.gson.GsonBuilder
import com.roamly.data.api.RoamlyApi
import com.roamly.data.prefs.UserPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
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

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @AuthInterceptor authInterceptor: Interceptor,
        @BaseUrlInterceptor baseUrlInterceptor: Interceptor
    ): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }
        return OkHttpClient.Builder()
            .addInterceptor(baseUrlInterceptor)   // rewrite URL first
            .addInterceptor(authInterceptor)       // then add auth header
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit {
        // Dummy base URL — BaseUrlInterceptor replaces host/port/scheme at runtime
        val gson = GsonBuilder().setLenient().create()
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
    fun providePalRepository(api: RoamlyApi): com.roamly.data.repository.PalRepository =
        com.roamly.data.repository.PalRepository(api)
}
