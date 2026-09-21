package com.fundata.callblocker.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import java.util.Locale

object DefaultDialerResolver {
    fun packageName(context: Context): String? = try {
        (context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager)
            .defaultDialerPackage
            ?.takeIf { it.isNotBlank() }
    } catch (t: Throwable) {
        DiagnosticsStore.saveError("Resolve default dialer package", t)
        null
    }

    fun callUiPackageName(context: Context): String? {
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.US)
        val candidates = try {
            installedPackages(context.packageManager)
                .asSequence()
                .filter { info -> info.applicationInfo?.enabled == true }
                .map { it.packageName }
                .filter { it.contains("incallui", ignoreCase = true) }
                .distinct()
                .toList()
        } catch (t: Throwable) {
            DiagnosticsStore.saveError("Resolve installed in-call UI package", t)
            emptyList()
        }

        return candidates.minWithOrNull(
            compareBy<String> { packageName ->
                when {
                    packageName == "com.samsung.android.incallui" -> 0
                    manufacturer.isNotBlank() && packageName.contains(manufacturer, ignoreCase = true) -> 1
                    packageName == "com.android.incallui" -> 2
                    else -> 3
                }
            }.thenBy { it }
        ) ?: packageName(context)
    }

    @Suppress("DEPRECATION")
    private fun installedPackages(packageManager: PackageManager) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
        } else {
            packageManager.getInstalledPackages(0)
        }
}
