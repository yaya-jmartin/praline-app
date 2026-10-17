package com.yaya.praline

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager

/**
 * "Home dungeon access": the two phones only pair and sync on the saved home Wi-Fi, recognised by its
 * network name. Anywhere else (a café, a friend's place) nothing is sent or answered.
 *
 * Reading a Wi-Fi's name needs the location permission on Android 10 and later (and location switched on);
 * it is asked once, only for that.
 */
object HomeWifi {
    private const val KEY = "home_ssid"
    /** Tests stand in for a phone at home. */
    @JvmField var assumeHome = false

    fun saved(c: Context): String = c.getSharedPreferences("app", Context.MODE_PRIVATE).getString(KEY, "") ?: ""
    fun save(c: Context, ssid: String) { c.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putString(KEY, ssid).apply() }
    fun forget(c: Context) { c.getSharedPreferences("app", Context.MODE_PRIVATE).edit().remove(KEY).apply() }

    fun canRead(c: Context): Boolean = c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** The name of the Wi-Fi this phone is on, or "" when it can't be read (no Wi-Fi, no permission, location off). */
    fun current(c: Context): String {
        if (!canRead(c)) return ""
        val wm = c.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return ""
        @Suppress("DEPRECATION")
        val s = try { wm.connectionInfo?.ssid ?: "" } catch (e: Exception) { "" }
        return clean(s)
    }

    /** Android wraps names in quotes and says "<unknown ssid>" when it won't tell. */
    fun clean(s: String): String {
        val t = s.trim()
        if (t.isEmpty() || t == "<unknown ssid>" || t == "0x") return ""
        return if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) t.substring(1, t.length - 1) else t
    }

    /** True on the saved home Wi-Fi: the only place the phones pair and sync. */
    fun here(c: Context): Boolean {
        if (assumeHome) return true
        val home = saved(c)
        return home.isNotEmpty() && current(c) == home
    }

    /** Saves the Wi-Fi this phone is on as home, after asking for the permission. [done] gets the name, or null. */
    fun setHere(a: MainActivity, done: (String?) -> Unit) {
        a.withPermission(Manifest.permission.ACCESS_FINE_LOCATION) { ok ->
            if (!ok) { done(null); return@withPermission }
            val now = current(a)
            if (now.isEmpty()) { done(null); return@withPermission }
            save(a, now)
            // announce this phone on the home network now, then sync
            Sync.stopServer(a); Sync.startServer(a)
            Sync.nudge(a)
            done(now)
        }
    }

    /** Explains, then saves this Wi-Fi as home. Shared by Settings and pairing. */
    fun ask(a: MainActivity, after: () -> Unit) {
        val s = Sheet(a, "Home dungeon access")
        s.body.addView(a.note("Praliné only pairs and syncs on your home Wi-Fi, so your cookbook never travels on other networks. " +
            "To recognise home by its name, Android asks for the location permission: it's only used to read the Wi-Fi's name.", 12.5f))
        s.button("Not now").button("Use this Wi-Fi", primary = true) {
            setHere(a) { name ->
                if (name == null) a.toast(if (!canRead(a)) "Without the permission, Praliné can't tell which Wi-Fi is home" else "Couldn't read the Wi-Fi name. Is Wi-Fi on, and location switched on?")
                else a.toast("Home is “$name”")
                after()
            }
        }
        s.show()
    }
}
