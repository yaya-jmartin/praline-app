package com.yaya.praline

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.MultiAutoCompleteTextView

/**
 * Amounts written inside a step ("add 2 eggs", "100 g of sugar") are live: when the number sits right
 * before one of the recipe's ingredients it scales with the servings wheel, even when it is only part
 * of that ingredient's total.
 */
object StepText {
    /** One amount found in a step: characters [start, end) hold the number, followed by [unit] and ingredient [ingredient]. */
    class Hit(val start: Int, val end: Int, val qty: Double, val unit: String, val ingredient: Int)

    private val FRACS = mapOf('½' to 0.5, '⅓' to 1.0 / 3, '⅔' to 2.0 / 3, '¼' to 0.25, '¾' to 0.75, '⅛' to 0.125)
    private val LINKS = arrayOf("of ", "de ", "d'", "d’", "du ", "des ")

    private fun isLetter(ch: Char) = Character.isLetter(ch)

    /** Name forms an ingredient can be written as in a step: the full name, its last and first words, singular or plural. */
    fun forms(name: String): List<String> {
        val out = ArrayList<String>()
        val n = name.lowercase().trim()
        if (n.isEmpty()) return out
        fun add(w: String) {
            val t = w.trim()
            if (t.length < 2 || out.contains(t)) return
            out.add(t)
            if (t.endsWith("es") && t.length > 4) out.add(t.substring(0, t.length - 2))
            if ((t.endsWith("s") || t.endsWith("x")) && t.length > 3) out.add(t.substring(0, t.length - 1))
            else { out.add(t + "s") }
        }
        add(n)
        val words = n.split(" ")
        if (words.size > 1) {
            val last = words[words.size - 1]; if (last.length >= 3) add(last)
            val first = words[0]; if (first.length >= 3) add(first)
        }
        return out
    }

    /** Reads a number at [i]: "2", "1.5", "1,5", "1/2", "1 1/2", "½", "1½". Returns (value, end) or null. */
    fun number(t: String, i0: Int): Pair<Double, Int>? {
        var i = i0
        var q = 0.0; var has = false
        val num = StringBuilder()
        while (i < t.length && (Character.isDigit(t[i]) || ((t[i] == '.' || t[i] == ',') && num.isNotEmpty() && i + 1 < t.length && Character.isDigit(t[i + 1])))) {
            num.append(if (t[i] == ',') '.' else t[i]); i++
        }
        if (num.isNotEmpty()) { q = num.toString().toDoubleOrNull() ?: return null; has = true }
        if (has && i < t.length && t[i] == '/') {
            var j = i + 1; val d = StringBuilder()
            while (j < t.length && Character.isDigit(t[j])) { d.append(t[j]); j++ }
            val dv = d.toString().toDoubleOrNull()
            if (dv != null && dv > 0) { q /= dv; i = j }
        } else if (has && i + 1 < t.length && t[i] == ' ' && Character.isDigit(t[i + 1])) {
            var j = i + 1; val a = StringBuilder()
            while (j < t.length && Character.isDigit(t[j])) { a.append(t[j]); j++ }
            if (j < t.length && t[j] == '/') {
                var k = j + 1; val b = StringBuilder()
                while (k < t.length && Character.isDigit(t[k])) { b.append(t[k]); k++ }
                val dv = b.toString().toDoubleOrNull()
                if (dv != null && dv > 0) { q += a.toString().toDouble() / dv; i = k }
            }
        }
        if (has && i + 1 < t.length && t[i] == ' ' && FRACS.containsKey(t[i + 1])) i++
        if (i < t.length && FRACS.containsKey(t[i])) { q += FRACS[t[i]]!!; has = true; i++ }
        return if (has && q > 0) Pair(q, i) else null
    }

    private fun skipSpaces(t: String, i0: Int): Int { var i = i0; while (i < t.length && t[i] == ' ') i++; return i }

    /** Finds every amount in [text] that belongs to one of [ings]. */
    fun find(text: String, ings: List<Ingredient>): List<Hit> {
        val out = ArrayList<Hit>()
        if (ings.isEmpty()) return out
        val low = text.lowercase()
        val allForms = ArrayList<List<String>>()
        for (ing in ings) allForms.add(forms(ing.name))
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            val starts = (Character.isDigit(ch) || FRACS.containsKey(ch)) && (i == 0 || !(isLetter(text[i - 1]) || Character.isDigit(text[i - 1]) || text[i - 1] == '.' || text[i - 1] == ','))
            if (!starts) { i++; continue }
            val n = number(text, i)
            if (n == null) { i++; continue }
            var j = skipSpaces(text, n.second)
            // optional unit
            var unit = ""
            var k = j
            while (k < text.length && (isLetter(text[k]) || text[k] == '.')) k++
            if (k > j) {
                val w = low.substring(j, k).trimEnd('.')
                if (Qty.UNITS.contains(w)) { unit = text.substring(j, k).trimEnd('.'); j = skipSpaces(text, k) }
            }
            // optional "of" / "de" / "d'"
            for (l in LINKS) if (low.startsWith(l, j)) { j = skipSpaces(text, j + l.length); break }
            // the ingredient, longest form first
            var best = -1; var bestLen = 0
            for (g in allForms.indices) for (f in allForms[g]) {
                if (f.length > bestLen && low.startsWith(f, j)) {
                    val after = j + f.length
                    if (after >= low.length || !isLetter(low[after])) { best = g; bestLen = f.length }
                }
            }
            if (best >= 0) {
                out.add(Hit(i, n.second, n.first, unit, best))
                i = j + bestLen
            } else i = Math.max(i + 1, n.second)
        }
        return out
    }

    /** The step as plain text with its amounts scaled (used by tests and the notification label). */
    fun plain(text: String, ings: List<Ingredient>, scale: Double): String {
        if (scale == 1.0) return text
        val hits = find(text, ings)
        val sb = StringBuilder()
        var last = 0
        for (h in hits) { sb.append(text, last, h.start).append(Qty.format(h.qty * scale, h.unit)); last = h.end }
        sb.append(text, last, text.length)
        return sb.toString()
    }

    /** The step with its live amounts in bold; raspberry when they differ from what was written. */
    fun styled(text: String, ings: List<Ingredient>, scale: Double): CharSequence {
        val hits = find(text, ings)
        if (hits.isEmpty()) return text
        val sb = SpannableStringBuilder()
        var last = 0
        for (h in hits) {
            sb.append(text, last, h.start)
            val s = sb.length
            sb.append(if (scale == 1.0) text.substring(h.start, h.end) else Qty.format(h.qty * scale, h.unit))
            sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (scale != 1.0) sb.setSpan(ForegroundColorSpan(C.RASP), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            last = h.end
        }
        sb.append(text, last, text.length)
        return sb
    }

    /** Short summary for the editor: "2 eggs · 100 g sugar". */
    fun summary(text: String, ings: List<Ingredient>): String {
        val sb = StringBuilder()
        for (h in find(text, ings)) {
            if (sb.isNotEmpty()) sb.append(" · ")
            sb.append(text, h.start, h.end)
            if (h.unit.isNotEmpty()) sb.append(' ').append(h.unit)
            sb.append(' ').append(ings[h.ingredient].name)
        }
        return sb.toString()
    }
}

/** Ingredient names to complete from: everything already in the grimoire, then a pantry of common ones. */
object Pantry {
    val COMMON = arrayOf("flour", "sugar", "caster sugar", "icing sugar", "brown sugar", "butter", "unsalted butter", "eggs", "egg yolks",
        "egg whites", "milk", "cream", "double cream", "crème fraîche", "salt", "pepper", "olive oil", "garlic", "onion", "shallot",
        "tomatoes", "lemon", "lemon zest", "orange", "vanilla extract", "baking powder", "baking soda", "dark chocolate", "cocoa powder",
        "honey", "yeast", "water", "rice", "pasta", "parmesan", "mozzarella", "basil", "parsley", "thyme", "rosemary", "cinnamon",
        "ginger", "nutmeg", "chicken", "beef", "bacon", "potatoes", "carrots", "mushrooms", "spinach", "apples", "pears", "almonds",
        "hazelnuts", "pistachios", "mascarpone", "ladyfingers", "coffee", "rum", "puff pastry", "stock", "soy sauce", "chili")

    fun names(): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (r in Store.recipes) for (v in r.variants) for (i in v.ingredients) {
            val n = i.name.trim(); if (n.isNotEmpty() && seen.add(n.lowercase())) out.add(n)
        }
        for (n in COMMON) if (seen.add(n)) out.add(n)
        return out
    }

    /** "200 g fl" -> ("200 g ", "fl"): the amount part stays, only the name is completed. */
    fun split(line: String): Pair<String, String> {
        val ing = Qty.parse(line)
        if (ing.qty == null) return Pair("", line.trimStart())
        val name = ing.name
        val at = line.lastIndexOf(name)
        return if (name.isEmpty() || at < 0) Pair(line, "") else Pair(line.substring(0, at), name)
    }

    fun matches(part: String, names: List<String>, max: Int = 8): List<String> {
        val p = part.trim().lowercase()
        val out = ArrayList<String>()
        if (p.isEmpty()) return out
        for (n in names) if (n.lowercase().startsWith(p) && n.lowercase() != p) { out.add(n); if (out.size >= max) return out }
        for (n in names) if (!out.contains(n) && n.lowercase().contains(" $p") && n.lowercase() != p) { out.add(n); if (out.size >= max) return out }
        return out
    }
}

/** Suggests ingredient names for a whole line, keeping the amount the user typed in front. */
class IngredientAdapter(c: Context, private val names: List<String>) : ArrayAdapter<String>(c, android.R.layout.simple_dropdown_item_1line) {
    private val shown = ArrayList<String>()
    override fun getCount() = shown.size
    override fun getItem(position: Int): String = shown[position]
    override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
        val t = (convertView as? android.widget.TextView) ?: context.body("", 15f).also { it.setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(10)) }
        t.text = shown[position]
        return t
    }
    private val filter = object : Filter() {
        override fun performFiltering(cs: CharSequence?): FilterResults {
            val line = cs?.toString() ?: ""
            val sp = Pantry.split(line)
            val l = ArrayList<String>()
            for (n in Pantry.matches(sp.second, names)) l.add(sp.first + n)
            val r = FilterResults(); r.values = l; r.count = l.size; return r
        }
        @Suppress("UNCHECKED_CAST")
        override fun publishResults(cs: CharSequence?, r: FilterResults?) {
            shown.clear()
            val v = r?.values as? List<String>
            if (v != null) shown.addAll(v)
            if (shown.isEmpty()) notifyDataSetInvalidated() else notifyDataSetChanged()
        }
        override fun convertResultToString(v: Any?): CharSequence = v?.toString() ?: ""
    }
    override fun getFilter(): Filter = filter
}

/** In a step, completes an ingredient name only when an amount was just typed before it ("2 eg" -> "2 eggs"). */
class AmountTokenizer : MultiAutoCompleteTextView.Tokenizer {
    override fun findTokenStart(text: CharSequence, cursor: Int): Int {
        var i = cursor
        while (i > 0 && Character.isLetter(text[i - 1])) i--
        val wordStart = i
        // skip "of " / a unit, back to a number
        var j = wordStart
        while (j > 0 && text[j - 1] == ' ') j--
        var k = j
        while (k > 0 && (Character.isLetter(text[k - 1]) || text[k - 1] == '.')) k--
        if (k < j) {
            val w = text.subSequence(k, j).toString().lowercase().trimEnd('.')
            if (Qty.UNITS.contains(w) || w == "of" || w == "de") { j = k; while (j > 0 && text[j - 1] == ' ') j-- }
        }
        return if (j > 0 && (Character.isDigit(text[j - 1]) || "½⅓⅔¼¾⅛".indexOf(text[j - 1]) >= 0)) wordStart else cursor
    }
    override fun findTokenEnd(text: CharSequence, cursor: Int): Int {
        var i = cursor
        while (i < text.length && Character.isLetter(text[i])) i++
        return i
    }
    override fun terminateToken(text: CharSequence): CharSequence = text
}
