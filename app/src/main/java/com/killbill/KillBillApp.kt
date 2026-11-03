package com.killbill

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.killbill.data.BillRepository
import com.killbill.data.Prefs
import com.killbill.data.db.AppDatabase
import com.killbill.stats.StatsRepository

class KillBillApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.get(this) }
    val prefs: Prefs by lazy { Prefs(this) }
    val billRepo: BillRepository by lazy { BillRepository(database.transactionDao(), database.categoryDao()) }
    val statsRepo: StatsRepository by lazy { StatsRepository(database.transactionDao()) }

    override fun onCreate() {
        super.onCreate()
        com.killbill.util.DiagLog.init(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "killbill_service"
    }
}
