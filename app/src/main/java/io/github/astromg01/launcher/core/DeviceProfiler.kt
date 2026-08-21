package io.github.astromg01.launcher.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build

object DeviceProfiler {
    fun read(context: Context): DeviceProfile {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)

        return DeviceProfile(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidSdk = Build.VERSION.SDK_INT,
            availableProcessors = Runtime.getRuntime().availableProcessors(),
            totalMemoryMb = memory.totalMem / (1024L * 1024L)
        )
    }
}
