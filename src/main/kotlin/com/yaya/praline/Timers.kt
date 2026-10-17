package com.yaya.praline

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import org.json.JSONArray

/**
 * Pistachio-style step timers: several can run at once, they survive the app being
 * closed, and they ring on the alarm volume. When Pistachio itself can take timers
 * (its next version), Praliné hands them over instead and only shows the countdown.
 */
object Timers {
    const val IDLE = 0
    const val RUNNING = 1
    const val PAUSED = 2
    const val DONE = 3
    const val PISTACHIO = "com.yaya.timer"
    const val EXTRA_ID = "timer_id"
    const val CH_RUN = "praline_timers"
    const val CH_DONE = "praline_timer_done"
    const val CH_STOPPED = "praline_timer_stopped"
    /** Sent with SET_TIMER when the timer repeats; Pistachio advertises it understands it with [META_ROUNDS]. */
    const val EXTRA_ROUNDS = "com.yaya.timer.extra.ROUNDS"
    const val META_ROUNDS = "com.yaya.timer.ROUNDS"

    private fun p(c: Context): SharedPreferences = c.getSharedPreferences("timers", Context.MODE_PRIVATE)

    fun idFor(recipeId: String, variant: Int, step: Int) = "$recipeId#$variant#$step"

    fun state(c: Context, id: String) = p(c).getInt("s@$id", IDLE)
    fun label(c: Context, id: String) = p(c).getString("l@$id", "") ?: ""
    fun endAt(c: Context, id: String) = p(c).getLong("e@$id", 0L)
    fun setMs(c: Context, id: String) = p(c).getLong("set@$id", 0L)
    fun external(c: Context, id: String) = p(c).getBoolean("x@$id", false)
    fun rounds(c: Context, id: String) = Math.max(1, p(c).getInt("rn@$id", 1))
    fun round(c: Context, id: String) = Math.max(1, p(c).getInt("rd@$id", 1))
    /** The timer ran its whole course (all rounds) in this quest: the step shows a small "done" pill. */
    fun completed(c: Context, id: String) = p(c).getBoolean("c@$id", false)
    /** Seconds before the next round starts, while a repeating timer is between rounds. */
    fun gapLeft(c: Context, id: String): Long =
        if (state(c, id) == RUNNING) Math.max(0L, p(c).getLong("g@$id", 0L) - System.currentTimeMillis()) else 0L

    /** Pause between the rounds of a repeating timer (Settings). */
    fun roundGapSec(c: Context): Int = c.getSharedPreferences("app", Context.MODE_PRIVATE).getInt("round_gap", 5)
    /** Whether timers go to Pistachio when it is installed (Settings). */
    fun handoffOn(c: Context): Boolean = c.getSharedPreferences("app", Context.MODE_PRIVATE).getBoolean("handoff", true)

    fun remainingMs(c: Context, id: String): Long = when (state(c, id)) {
        RUNNING -> if (gapLeft(c, id) > 0) setMs(c, id) else Math.max(0L, endAt(c, id) - System.currentTimeMillis())
        PAUSED -> p(c).getLong("r@$id", 0L)
        DONE -> 0L
        else -> setMs(c, id)
    }

    fun active(c: Context): List<String> {
        val arr = JSONArray(p(c).getString("ids", "[]"))
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) { val id = arr.getString(i); if (state(c, id) != IDLE) out.add(id) }
        return out
    }

    private fun setActive(c: Context, id: String, on: Boolean) {
        val arr = JSONArray(p(c).getString("ids", "[]"))
        val keep = JSONArray()
        for (i in 0 until arr.length()) if (arr.getString(i) != id) keep.put(arr.getString(i))
        if (on) keep.put(id)
        p(c).edit().putString("ids", keep.toString()).apply()
    }

    /** True when the installed Pistachio can take timers from other apps. */
    fun pistachioTakesTimers(c: Context): Boolean = try {
        c.packageManager.resolveActivity(pistachioIntent(60, "x"), 0) != null
    } catch (e: Exception) { false }

    /** True when the installed Pistachio also repeats timers for several rounds. */
    fun pistachioTakesRounds(c: Context): Boolean = try {
        val ri = c.packageManager.resolveActivity(pistachioIntent(60, "x"), android.content.pm.PackageManager.GET_META_DATA)
        ri?.activityInfo?.metaData?.getBoolean(META_ROUNDS, false) == true
    } catch (e: Exception) { false }

    fun pistachioIntent(sec: Int, label: String, rounds: Int = 1): Intent {
        val i = Intent(AlarmClock.ACTION_SET_TIMER).setPackage(PISTACHIO)
            .putExtra(AlarmClock.EXTRA_LENGTH, sec).putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (rounds > 1) i.putExtra(EXTRA_ROUNDS, rounds)
        return i
    }

    /** Whether a timer of [rounds] rounds would run in Pistachio right now. */
    fun wouldHandOff(c: Context, rounds: Int): Boolean =
        handoffOn(c) && pistachioTakesTimers(c) && (rounds <= 1 || pistachioTakesRounds(c))

    fun start(c: Context, id: String, ms: Long, label: String, rounds: Int = 1) {
        if (ms <= 0) return
        stopRing(c)
        try { nm(c).cancel(nid(id, 1)); nm(c).cancel(nid(id, 3)) } catch (e: Exception) { }
        dismissStopped(c, id)
        val n = Math.max(1, rounds)
        val end = System.currentTimeMillis() + ms
        var handed = false
        if (wouldHandOff(c, n)) {
            handed = try { c.startActivity(pistachioIntent((ms / 1000).toInt(), label, n)); true } catch (e: Exception) { false }
        }
        p(c).edit().putInt("s@$id", RUNNING).putLong("e@$id", end).putLong("set@$id", ms).putString("l@$id", label)
            .putBoolean("x@$id", handed).putInt("rn@$id", n).putInt("rd@$id", 1).putLong("g@$id", 0L).putBoolean("c@$id", false).apply()
        setActive(c, id, true)
        if (!handed) { schedule(c, id, end); notifyRunning(c, id) }
    }

    fun pause(c: Context, id: String) {
        if (state(c, id) != RUNNING || external(c, id)) return
        p(c).edit().putInt("s@$id", PAUSED).putLong("r@$id", remainingMs(c, id)).apply()
        cancel(c, id); notifyRunning(c, id)
    }

    fun resume(c: Context, id: String) {
        if (state(c, id) != PAUSED) return
        val end = System.currentTimeMillis() + p(c).getLong("r@$id", 0L)
        p(c).edit().putInt("s@$id", RUNNING).putLong("e@$id", end).apply()
        schedule(c, id, end); notifyRunning(c, id)
    }

    fun adjust(c: Context, id: String, delta: Long) {
        if (external(c, id)) return
        when (state(c, id)) {
            RUNNING -> { val end = endAt(c, id) + delta; if (end - System.currentTimeMillis() < 1000) return
                p(c).edit().putLong("e@$id", end).apply(); schedule(c, id, end); notifyRunning(c, id) }
            PAUSED -> { val r = p(c).getLong("r@$id", 0L) + delta; if (r < 1000) return
                p(c).edit().putLong("r@$id", r).apply(); notifyRunning(c, id) }
            DONE -> start(c, id, delta, label(c, id))
        }
    }

    /** Back to the start: not running, not done. */
    fun reset(c: Context, id: String) {
        p(c).edit().putInt("s@$id", IDLE).putBoolean("c@$id", false).putInt("rd@$id", 1).apply()
        clearRun(c, id)
    }

    private fun clearRun(c: Context, id: String) {
        setActive(c, id, false)
        cancel(c, id)
        nm(c).cancel(nid(id, 0)); nm(c).cancel(nid(id, 1))
        if (active(c).none { state(c, it) == DONE }) stopRing(c)
    }

    /** "Stop" on a ringing timer: silences it, marks the step done, and keeps a little card and notification saying when. */
    fun stop(c: Context, id: String, at: Long = System.currentTimeMillis()) {
        if (state(c, id) != DONE) { reset(c, id); return }
        p(c).edit().putInt("s@$id", IDLE).putBoolean("c@$id", true).apply()
        clearRun(c, id)
        addStopped(c, id, label(c, id), at)
        notifyStopped(c, id, at)
    }

    /** Forgets every timer of one quest (recipe + variant), running or not. The "done at" cards stay until closed. */
    fun resetQuest(c: Context, recipeId: String, variant: Int, steps: Int) {
        for (i in 0 until Math.max(steps, 40)) {
            val id = idFor(recipeId, variant, i)
            if (state(c, id) != IDLE || completed(c, id)) reset(c, id)
        }
    }

    /** Timers of this quest still counting down, paused or ringing. */
    fun liveInQuest(c: Context, recipeId: String, variant: Int, steps: Int): Int {
        var n = 0
        for (i in 0 until steps) { val st = state(c, idFor(recipeId, variant, i)); if (st != IDLE) n++ }
        return n
    }

    /** Live timers of any variant of a recipe (before deleting it). */
    fun liveForRecipe(c: Context, recipeId: String): List<String> {
        val out = ArrayList<String>()
        for (id in active(c)) if (id.startsWith("$recipeId#")) out.add(id)
        return out
    }

    // --- the "done at" cards that stay after Stop -------------------------------
    class Stopped(val id: String, val label: String, val at: Long)

    fun stoppedCards(c: Context): List<Stopped> {
        val arr = try { JSONArray(p(c).getString("stopped", "[]")) } catch (e: Exception) { JSONArray() }
        val out = ArrayList<Stopped>()
        for (i in 0 until arr.length()) { val o = arr.optJSONObject(i) ?: continue; out.add(Stopped(o.optString("id"), o.optString("l"), o.optLong("at"))) }
        return out
    }

    private fun saveStopped(c: Context, l: List<Stopped>) {
        val arr = JSONArray()
        val from = Math.max(0, l.size - 6)
        for (i in from until l.size) arr.put(org.json.JSONObject().put("id", l[i].id).put("l", l[i].label).put("at", l[i].at))
        p(c).edit().putString("stopped", arr.toString()).apply()
    }

    private fun addStopped(c: Context, id: String, label: String, at: Long) {
        val l = ArrayList<Stopped>(); for (x in stoppedCards(c)) if (x.id != id) l.add(x)
        l.add(Stopped(id, label, at)); saveStopped(c, l)
    }

    fun dismissStopped(c: Context, id: String) {
        val all = stoppedCards(c)
        if (all.none { it.id == id }) return
        val l = ArrayList<Stopped>(); for (x in all) if (x.id != id) l.add(x)
        saveStopped(c, l)
        try { nm(c).cancel(nid(id, 3)) } catch (e: Exception) { }
    }

    fun clock(at: Long): String = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(at))

    /** The alarm went off (or the screen noticed the time is up). */
    fun finishIfDue(c: Context, id: String) {
        if (state(c, id) != RUNNING) return
        val now = System.currentTimeMillis()
        if (endAt(c, id) - now > 1500) { if (!external(c, id)) schedule(c, id, endAt(c, id)); return }
        if (external(c, id)) {  // Pistachio rang for it; the step is done here
            p(c).edit().putInt("s@$id", IDLE).putBoolean("c@$id", true).apply()
            clearRun(c, id); return
        }
        val rd = round(c, id)
        if (rd < rounds(c, id)) {
            // another round: a short chime, a few seconds' pause, then it runs again by itself
            val gap = roundGapSec(c) * 1000L
            val end = now + gap + setMs(c, id)
            p(c).edit().putInt("rd@$id", rd + 1).putLong("g@$id", now + gap).putLong("e@$id", end).apply()
            chime(c)
            schedule(c, id, end); notifyRunning(c, id)
            return
        }
        p(c).edit().putInt("s@$id", DONE).apply()
        nm(c).cancel(nid(id, 0))
        ring(c)
        notifyDone(c, id)
    }

    fun finishAllDue(c: Context) { for (id in active(c)) finishIfDue(c, id) }

    fun restore(c: Context) {
        for (id in active(c)) if (state(c, id) == RUNNING && !external(c, id)) {
            if (System.currentTimeMillis() >= endAt(c, id)) finishIfDue(c, id) else { schedule(c, id, endAt(c, id)); notifyRunning(c, id) }
        }
    }

    // --- alarm ---------------------------------------------------------------
    private fun code(id: String, base: Int) = 100 + (id.hashCode() and 0x3fff) * 4 + base
    private fun nid(id: String, k: Int) = code(id, k)

    fun intent(c: Context, action: String, id: String): Intent =
        Intent(c, TimerReceiver::class.java).setAction(action).setData(Uri.parse("praline://timer/" + Uri.encode(id))).putExtra(EXTRA_ID, id)

    private fun alarmPi(c: Context, id: String) = PendingIntent.getBroadcast(c, code(id, 2), intent(c, TimerReceiver.DONE, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun schedule(c: Context, id: String, at: Long) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val pi = alarmPi(c, id)
        val show = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
        } catch (e: SecurityException) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
    }

    private fun cancel(c: Context, id: String) { c.getSystemService(AlarmManager::class.java)?.cancel(alarmPi(c, id)) }

    // --- notifications -------------------------------------------------------
    private fun nm(c: Context) = c.getSystemService(NotificationManager::class.java)!!

    fun channels(c: Context) {
        val run = NotificationChannel(CH_RUN, "Running timers", NotificationManager.IMPORTANCE_LOW)
        run.setSound(null, null); run.setShowBadge(false)
        val done = NotificationChannel(CH_DONE, "Time's up", NotificationManager.IMPORTANCE_HIGH)
        done.setSound(null, null); done.enableVibration(true)
        val stopped = NotificationChannel(CH_STOPPED, "Finished timers", NotificationManager.IMPORTANCE_LOW)
        stopped.setSound(null, null); stopped.setShowBadge(false)
        nm(c).createNotificationChannel(run); nm(c).createNotificationChannel(done); nm(c).createNotificationChannel(stopped)
    }

    private fun open(c: Context) = PendingIntent.getActivity(c, 1, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
    private fun action(c: Context, id: String, act: String, k: Int) = PendingIntent.getBroadcast(c, code(id, 3) * 8 + k, intent(c, act, id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun icon(c: Context): Int = c.resources.getIdentifier("ic_notif", "drawable", c.packageName)

    fun notifyRunning(c: Context, id: String) {
        try {
            channels(c)
            val running = state(c, id) == RUNNING
            val b = Notification.Builder(c, CH_RUN).setSmallIcon(icon(c)).setContentTitle(label(c, id))
                .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open(c)).setColor(C.TIMER)
            val n = rounds(c, id)
            val roundText = if (n > 1) "Round ${round(c, id)} of $n" else ""
            if (running) {
                b.setUsesChronometer(true).setChronometerCountDown(true).setWhen(endAt(c, id)).setShowWhen(true)
                if (roundText.isNotEmpty()) b.setContentText(roundText)
            } else b.setContentText((if (roundText.isNotEmpty()) "$roundText · " else "") + "Paused · " + fmtClock(remainingMs(c, id) / 1000))
            b.addAction(Notification.Action.Builder(null, if (running) "Pause" else "Resume", action(c, id, if (running) TimerReceiver.PAUSE else TimerReceiver.RESUME, 0)).build())
            b.addAction(Notification.Action.Builder(null, "+1 min", action(c, id, TimerReceiver.PLUS, 1)).build())
            b.addAction(Notification.Action.Builder(null, "Cancel", action(c, id, TimerReceiver.RESET, 2)).build())
            nm(c).notify(nid(id, 0), b.build())
        } catch (e: SecurityException) { }
    }

    private fun notifyDone(c: Context, id: String) {
        try {
            channels(c)
            val b = Notification.Builder(c, CH_DONE).setSmallIcon(icon(c)).setContentTitle("Time's up!")
                .setContentText(label(c, id)).setCategory(Notification.CATEGORY_ALARM).setColor(C.RASP)
                .setContentIntent(open(c)).setDeleteIntent(action(c, id, TimerReceiver.STOP, 4))
                .addAction(Notification.Action.Builder(null, "Stop", action(c, id, TimerReceiver.STOP, 5)).build())
                .addAction(Notification.Action.Builder(null, "+1 min", action(c, id, TimerReceiver.PLUS, 6)).build())
            nm(c).notify(nid(id, 1), b.build())
        } catch (e: SecurityException) { }
    }

    /** Stays in the shade after Stop: "✓ Simmer · done at 14:32". */
    private fun notifyStopped(c: Context, id: String, at: Long) {
        try {
            channels(c)
            val b = Notification.Builder(c, CH_STOPPED).setSmallIcon(icon(c)).setContentTitle("✓ " + label(c, id))
                .setContentText("Done at " + clock(at)).setColor(C.PIST).setShowWhen(true).setWhen(at)
                .setContentIntent(open(c)).setAutoCancel(true)
            nm(c).notify(nid(id, 3), b.build())
        } catch (e: SecurityException) { }
    }

    // --- sound ---------------------------------------------------------------
    private var ringtone: Ringtone? = null
    private var ringStarted = 0L

    private fun ring(c: Context) {
        if (ringtone?.isPlaying == true) return
        try {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(c, RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val r = RingtoneManager.getRingtone(c, uri) ?: return
            r.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            if (Build.VERSION.SDK_INT >= 28) r.isLooping = true
            r.play()
            ringtone = r; ringStarted = System.currentTimeMillis()
        } catch (e: Exception) { }
    }

    /** A short ring between rounds, stopped after a few seconds. */
    private fun chime(c: Context) {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return
            val r = RingtoneManager.getRingtone(c, uri) ?: return
            r.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            r.play()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(object : Runnable { override fun run() { try { r.stop() } catch (e: Exception) { } } }, 3000)
        } catch (e: Exception) { }
    }

    fun ringing(): Boolean = ringtone?.isPlaying == true

    fun stopRing(c: Context) { try { ringtone?.stop() } catch (e: Exception) { }; ringtone = null }
}

class TimerReceiver : BroadcastReceiver() {
    companion object {
        const val DONE = "com.yaya.praline.TIMER_DONE"
        const val PAUSE = "com.yaya.praline.TIMER_PAUSE"
        const val RESUME = "com.yaya.praline.TIMER_RESUME"
        const val PLUS = "com.yaya.praline.TIMER_PLUS"
        const val RESET = "com.yaya.praline.TIMER_RESET"
        const val STOP = "com.yaya.praline.TIMER_STOP"
    }
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getStringExtra(Timers.EXTRA_ID) ?: return
        when (i.action) {
            DONE -> Timers.finishIfDue(c, id)
            PAUSE -> Timers.pause(c, id)
            RESUME -> Timers.resume(c, id)
            PLUS -> Timers.adjust(c, id, 60_000L)
            RESET -> Timers.reset(c, id)
            STOP -> Timers.stop(c, id)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { Timers.restore(c) }
}
