package com.roamly.tracker

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point.
 *
 * [HiltAndroidApp] triggers Hilt's component generation and wires up dependency injection
 * for all @AndroidEntryPoint-annotated classes (Activities, Services, BroadcastReceivers).
 *
 * Implements [Configuration.Provider] to integrate WorkManager with Hilt so that
 * [androidx.hilt.work.HiltWorker]-annotated workers receive injected dependencies.
 */
@HiltAndroidApp
class RoamlyApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
