package com.avestv.tv

import android.content.Context
import android.os.Build
import java.util.UUID

class TvPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("avestv_prefs", Context.MODE_PRIVATE)

    val deviceId: String
        get() {
            val existing = prefs.getString(KEY_DEVICE_ID, null)
            if (!existing.isNullOrBlank()) return existing
            val created = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, created).apply()
            return created
        }

    var deviceName: String
        get() {
            val saved = prefs.getString(KEY_DEVICE_NAME, null)
            if (!saved.isNullOrBlank()) return saved
            val model = Build.MODEL?.trim().orEmpty()
            return if (model.isNotEmpty()) "客厅电视 ($model)" else "客厅电视"
        }
        set(value) {
            val clean = value.trim().take(40).ifEmpty { "客厅电视" }
            prefs.edit().putString(KEY_DEVICE_NAME, clean).apply()
        }

    var autoStartEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_START, value).apply()
        }

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_AUTO_START = "auto_start"
    }
}
