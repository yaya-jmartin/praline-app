package com.yaya.praline

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One moment that felt slow or awkward: when, on which screen, in which version, and its description. */
class FrictionEntry(val at: Long, val screen: String, val version: String, val note: String)

/**
 * The friction log, as in Pistachio: two fingers resting anywhere save the moment, a note box opens
 * right away for a description, and Settings lists them and sends them on through Android's share sheet
 * (so Discord's own picker chooses the channel or DM).
 */
object Friction {
    /** How long two fingers rest on the screen to save a moment. */
    const val HOLD_MS = 600L
    private const val KEY = "friction_log"
    private const val MAX = 200

    private fun prefs(c: Context) = c.getSharedPreferences("app", Context.MODE_PRIVATE)

    fun all(c: Context): List<FrictionEntry> {
        val arr = try { JSONArray(prefs(c).getString(KEY, "[]")) } catch (e: Exception) { JSONArray() }
        val out = ArrayList<FrictionEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(FrictionEntry(o.optLong("t"), o.optString("s"), o.optString("v"), o.optString("n")))
        }
        return out
    }

    private fun save(c: Context, l: List<FrictionEntry>) {
        val arr = JSONArray()
        val from = Math.max(0, l.size - MAX)
        for (i in from until l.size) { val e = l[i]; arr.put(JSONObject().put("t", e.at).put("s", e.screen).put("v", e.version).put("n", e.note)) }
        prefs(c).edit().putString(KEY, arr.toString()).apply()
    }

    fun version(c: Context): String = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }

    fun add(c: Context, screen: String, at: Long = System.currentTimeMillis()): FrictionEntry {
        val e = FrictionEntry(at, screen, version(c), "")
        val l = ArrayList(all(c)); l.add(e)
        save(c, l)
        return e
    }

    /** The description can be as long as needed, over several lines. */
    fun setNote(c: Context, at: Long, note: String) {
        val l = ArrayList<FrictionEntry>()
        for (e in all(c)) l.add(if (e.at == at) FrictionEntry(e.at, e.screen, e.version, note.trim()) else e)
        save(c, l)
    }

    fun remove(c: Context, at: Long) { val l = ArrayList<FrictionEntry>(); for (e in all(c)) if (e.at != at) l.add(e); save(c, l) }
    fun clear(c: Context) { prefs(c).edit().remove(KEY).apply() }

    fun time(at: Long): String = SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(at))

    /** The whole log as plain text, ready to paste or send. Multi-line descriptions stay indented. */
    fun text(c: Context): String {
        val l = all(c)
        val sb = StringBuilder("Praliné friction log (" + l.size + ")\n")
        for (i in l.indices) {
            val e = l[i]
            sb.append('\n').append(i + 1).append(". ").append(time(e.at)).append(" · ").append(e.screen).append(" · v").append(e.version)
            if (e.note.isNotEmpty()) for (line in e.note.split("\n")) sb.append("\n   ").append(line)
        }
        return sb.toString()
    }

    /** Android's share sheet with the log: Discord (or anything else) picks where it goes. */
    fun shareIntent(c: Context): Intent {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Praliné friction log")
            .putExtra(Intent.EXTRA_TEXT, text(c))
        return Intent.createChooser(send, "Send friction log")
    }
}
