package com.paysync.gateway

import android.app.Application
import android.content.Context
import com.paysync.gateway.data.ApiClient
import com.paysync.gateway.data.GatewayRepository
import com.paysync.gateway.data.SettingsManager
import com.paysync.gateway.data.db.AppDatabase
import com.paysync.gateway.util.NetworkMonitor
import com.paysync.gateway.work.DispatchWorker
import com.paysync.gateway.work.PollingWorker

/**
 * Manual DI container (no Hilt/Koin, per spec): singletons are built once
 * here and reached via `(context.applicationContext as PaySyncApp).container`.
 */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val settings: SettingsManager = SettingsManager.get(appContext)
    val db: AppDatabase = AppDatabase.get(appContext)
    val api: ApiClient = ApiClient(settings)
    val networkMonitor: NetworkMonitor = NetworkMonitor(appContext)
    val repo: GatewayRepository = GatewayRepository(appContext, db, settings, api)
}

class PaySyncApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // 24/7 device truth source for the dashboard.
        runCatching { container.networkMonitor.start() }
        // Backup 15-min poller; cheap no-op until the backend is configured.
        runCatching { PollingWorker.schedule(this) }
        // CONNECTED-constrained queue safety net (survives FGS denial on boot).
        runCatching { DispatchWorker.schedulePeriodicDrain(this) }
    }
}
