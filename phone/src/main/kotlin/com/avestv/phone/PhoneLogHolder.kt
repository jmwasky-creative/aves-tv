package com.avestv.phone

import android.content.Context
import android.util.Log
import com.avestv.protocol.DebugLogStore
import java.io.File

object PhoneLogHolder {
    private val lock = Any()
    @Volatile
    private var instance: DebugLogStore? = null

    fun get(context: Context): DebugLogStore {
        instance?.let { return it }
        return synchronized(lock) {
            instance ?: DebugLogStore(
                logFile = File(context.applicationContext.filesDir, "logs/phone-sender.log"),
                onLog = { entry ->
                    val text = "[${entry.tag}] ${entry.message}"
                    when (entry.level) {
                        "ERROR" -> Log.e("AvesTV-Phone", text)
                        "WARN" -> Log.w("AvesTV-Phone", text)
                        else -> Log.i("AvesTV-Phone", text)
                    }
                },
            ).also { instance = it }
        }
    }
}
