package com.yaya.praline

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.ScrollView
import java.io.PrintWriter
import java.io.StringWriter

/** Keeps the last crash and shows it on the next launch, so it can be copied and sent. */
object Crashes {
    private fun stack(e: Throwable): String { val w = StringWriter(); e.printStackTrace(PrintWriter(w)); return w.toString() }

    fun install(c: Context) {
        val app = c.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(object : Thread.UncaughtExceptionHandler {
            override fun uncaughtException(t: Thread, e: Throwable) {
                try { app.getSharedPreferences("app", Activity.MODE_PRIVATE).edit().putString("last_crash", stack(e)).commit() } catch (x: Throwable) { }
                previous?.uncaughtException(t, e)
            }
        })
    }

    fun showLast(a: MainActivity) {
        val p = a.getSharedPreferences("app", Activity.MODE_PRIVATE)
        val trace = p.getString("last_crash", null) ?: return
        p.edit().remove("last_crash").apply()
        val sheet = Sheet(a, "Something went wrong")
        sheet.body.addView(a.note("Copy this and send it to Claude so it can be fixed.", 13f))
        val t = a.body(trace.take(900), 10.5f, C.INK2)
        t.setTextIsSelectable(true)
        val sv = ScrollView(a); sv.addView(t)
        sheet.body.addView(sv, lp(MATCH, a.dp(180)).m(t = a.dp(8)))
        sheet.button("Close").button("Copy", primary = true) {
            a.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Praliné error", trace))
        }.show()
    }
}
