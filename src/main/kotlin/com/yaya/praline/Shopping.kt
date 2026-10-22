package com.yaya.praline

import android.content.Intent
import android.view.Gravity
import java.util.Calendar

/**
 * The shopping list for what is planned from today to Sunday (1.4). Each planned day counts once at the recipe's
 * own servings; the same ingredient in the same unit is added up, and lines without an amount ("salt") show once.
 */
object Shopping {
    class Line(val name: String, val unit: String, var qty: Double?, val recipes: LinkedHashSet<String> = LinkedHashSet()) {
        fun text(): String {
            val q = qty ?: return "$name (to taste)"
            val v = Qty.format(q, unit)
            return if (unit.isEmpty()) "$v $name" else "$v $unit $name"
        }
    }

    /** The days still to come this week, today included. */
    fun days(now: Long = System.currentTimeMillis()): List<String> {
        val today = Calendar.getInstance(); today.timeInMillis = now
        val todayKey = Days.key(today)
        val mon = Days.monday(0, now)
        val out = ArrayList<String>()
        for (i in 0..6) { val d = mon.clone() as Calendar; d.add(Calendar.DAY_OF_MONTH, i); val k = Days.key(d); if (k >= todayKey) out.add(k) }
        return out
    }

    fun lines(now: Long = System.currentTimeMillis()): List<Line> {
        val out = LinkedHashMap<String, Line>()
        for (k in days(now)) {
            val v = Store.plan[k] ?: continue
            val parts = v.split("|")
            val r = Store.recipe(parts[0]) ?: continue
            val vi = Math.max(0, Math.min(if (parts.size > 1) parts[1].toIntOrNull() ?: 0 else 0, r.variants.size - 1))
            for (ing in r.variant(vi).ingredients) {
                val name = ing.name.trim()
                if (name.isEmpty()) continue
                val unit = if (ing.qty == null) "" else ing.unit.trim()
                val key = name.lowercase() + "|" + unit.lowercase() + "|" + (ing.qty == null)
                val line = out[key] ?: Line(name, unit, if (ing.qty == null) null else 0.0).also { out[key] = it }
                if (ing.qty != null) line.qty = (line.qty ?: 0.0) + ing.qty!!
                line.recipes.add(r.title)
            }
        }
        return out.values.sortedBy { it.name.lowercase() }
    }

    fun text(now: Long = System.currentTimeMillis()): String {
        val sb = StringBuilder("Shopping list · Praliné\n")
        for (l in lines(now)) sb.append("\n☐ ").append(l.text())
        return sb.toString()
    }

    fun show(a: MainActivity) {
        val ls = lines()
        val s = Sheet(a, "Shopping list")
        if (ls.isEmpty()) {
            s.body.addView(a.note("Nothing planned from today to Sunday yet. Plan or roll some days first.", 13f))
            s.button("Close").show()
            return
        }
        s.body.addView(a.note("Everything planned from today to Sunday, added up.", 12f), lp(MATCH, WRAP).m(b = a.dp(6)))
        for (l in ls) {
            val row = a.hbox(); row.gravity = Gravity.CENTER_VERTICAL; row.setPadding(0, a.dp(3), 0, a.dp(3))
            row.addView(a.body(l.text(), 14.5f), lp(0, WRAP, 1f))
            row.addView(a.note(l.recipes.joinToString(", "), 10.5f).also { it.gravity = Gravity.END; it.maxLines = 2 }, lp(a.dp(110), WRAP).m(l = a.dp(8)))
            s.body.addView(row)
        }
        s.button("Close").button("Share", primary = true) {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Shopping list").putExtra(Intent.EXTRA_TEXT, text())
            try { a.startActivity(Intent.createChooser(send, "Share the shopping list")) } catch (e: Exception) { a.toast("No app to share with") }
        }
        s.show()
    }
}
