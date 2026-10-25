package dev.hermano.hreceiver

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/** Все проверки разрешений и системных условий — в одном месте. */
object Perms {

    /** Разрешения, без которых BLE-скан и подключение не работают. */
    fun blePermissions(): List<String> {
        val out = mutableListOf(
            // FINE нужен для результатов скана на ВСЕХ версиях, т.к. мы не ставим
            // neverForLocation. COARSE обязан идти в том же запросе (Android 12+).
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            out += Manifest.permission.BLUETOOTH_SCAN
            out += Manifest.permission.BLUETOOTH_CONNECT
        }
        return out
    }

    fun allRuntimePermissions(): List<String> {
        val out = blePermissions().toMutableList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            out += Manifest.permission.POST_NOTIFICATIONS
        }
        return out
    }

    fun granted(ctx: Context, p: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun missingBle(ctx: Context): List<String> = blePermissions().filterNot { granted(ctx, it) }

    fun hasBle(ctx: Context): Boolean = missingBle(ctx).isEmpty()

    /** Пользователь выдал только «приблизительное» местоположение — скан будет пустым. */
    fun onlyCoarseLocation(ctx: Context): Boolean =
        granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) &&
            !granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasScan(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            granted(ctx, Manifest.permission.BLUETOOTH_SCAN) &&
                granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        else granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasConnect(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            granted(ctx, Manifest.permission.BLUETOOTH_CONNECT)
        else true

    fun hasNotifications(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            granted(ctx, Manifest.permission.POST_NOTIFICATIONS)
        else true

    fun locationEnabled(ctx: Context): Boolean = try {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        LocationManagerCompat.isLocationEnabled(lm)
    } catch (_: Exception) {
        true // не смогли проверить — не блокируем
    }

    fun ignoringBatteryOptimizations(ctx: Context): Boolean = try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(ctx.packageName)
    } catch (_: Exception) {
        true
    }

    fun isXiaomi(): Boolean {
        val m = (Build.MANUFACTURER ?: "").lowercase()
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")
    }
}
