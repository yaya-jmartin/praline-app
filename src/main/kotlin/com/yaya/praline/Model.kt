package com.yaya.praline

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.UUID

/** One ingredient line. [qty] is null for lines like "salt & pepper". */
class Ingredient(var qty: Double?, var unit: String, var name: String) {
    fun toJson(): JSONObject { val o = JSONObject(); if (qty != null) o.put("q", qty!!); o.put("u", unit); o.put("n", name); return o }

    /** "200 g sugar" at scale 1.5 -> "300 g". */
    fun amount(scale: Double): String {
        val q = qty ?: return ""
        val v = Qty.format(q * scale, unit)
        return if (unit.isEmpty()) v else "$v $unit"
    }

    /** The line as typed, for the editor. */
    fun raw(): String { val a = amount(1.0); return if (a.isEmpty()) name else "$a $name" }

    companion object {
        fun fromJson(o: JSONObject) = Ingredient(if (o.has("q")) o.getDouble("q") else null, o.optString("u"), o.optString("n"))
        fun parse(line: String): Ingredient = Qty.parse(line)
    }
}

/**
 * One step. A step can carry a Pistachio timer (run [rounds] times in a row), a hint picture
 * and a short video clip ([video] is the .mp4 in photos/, its thumbnail is [video] + ".jpg").
 */
class Step(var text: String, var timerSec: Int = 0, var timerLabel: String = "", var hint: String = "", var video: String = "", var rounds: Int = 1) {
    fun toJson(): JSONObject {
        val o = JSONObject(); o.put("t", text); o.put("s", timerSec); o.put("l", timerLabel); o.put("h", hint)
        if (video.isNotEmpty()) o.put("vd", video)
        if (rounds > 1) o.put("rn", rounds)
        return o
    }
    fun copy(): Step = Step(text, timerSec, timerLabel, hint, video, rounds)
    companion object { fun fromJson(o: JSONObject) = Step(o.optString("t"), o.optInt("s"), o.optString("l"), o.optString("h"), o.optString("vd"), Math.max(1, o.optInt("rn", 1))) }
}

class Variant(var name: String, val ingredients: MutableList<Ingredient> = ArrayList(), val steps: MutableList<Step> = ArrayList()) {
    fun toJson(): JSONObject {
        val o = JSONObject(); o.put("name", name)
        val i = JSONArray(); for (x in ingredients) i.put(x.toJson()); o.put("ing", i)
        val s = JSONArray(); for (x in steps) s.put(x.toJson()); o.put("steps", s)
        return o
    }
    fun copy(newName: String): Variant {
        val v = Variant(newName)
        for (x in ingredients) v.ingredients.add(Ingredient(x.qty, x.unit, x.name))
        for (x in steps) v.steps.add(x.copy())
        return v
    }
    companion object {
        fun fromJson(o: JSONObject): Variant {
            val v = Variant(o.optString("name", "Classic"))
            val i = o.optJSONArray("ing") ?: JSONArray(); for (k in 0 until i.length()) v.ingredients.add(Ingredient.fromJson(i.getJSONObject(k)))
            val s = o.optJSONArray("steps") ?: JSONArray(); for (k in 0 until s.length()) v.steps.add(Step.fromJson(s.getJSONObject(k)))
            return v
        }
    }
}

class Recipe(var id: String = UUID.randomUUID().toString()) {
    var title = ""
    var dessert = false
    var cls = "paladin"
    var tier = "B"
    var effort = 2
    var timeMin = 30
    var servings = 4
    var photo = ""        // filtered picture shown everywhere (file in photos/)
    var photoOrig = ""    // untouched shot, so the filter can be changed later
    var filter = ""
    var strength = 0.8f
    var lastCooked = 0L
    /** Fame for finishing it; 0 = worked out from the effort. */
    var fame = 0
    /** Kitchen tools it needs: keys from [Equipment.all] or free words. */
    val equipment: MutableList<String> = ArrayList()
    var created = System.currentTimeMillis()
    var updated = System.currentTimeMillis()
    val variants: MutableList<Variant> = ArrayList()

    fun variant(i: Int): Variant {
        if (variants.isEmpty()) variants.add(Variant("Classic"))
        return variants[Math.max(0, Math.min(i, variants.size - 1))]
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id); o.put("title", title); o.put("dessert", dessert); o.put("cls", cls); o.put("tier", tier)
        o.put("effort", effort); o.put("time", timeMin); o.put("serv", servings); o.put("photo", photo); o.put("orig", photoOrig)
        o.put("filter", filter); o.put("str", strength.toDouble()); o.put("cooked", lastCooked); o.put("created", created); o.put("updated", updated)
        if (fame > 0) o.put("fame", fame)
        val eq = JSONArray(); for (x in equipment) eq.put(x); o.put("equip", eq)
        val v = JSONArray(); for (x in variants) v.put(x.toJson()); o.put("variants", v)
        return o
    }

    fun photos(): List<String> {
        val out = ArrayList<String>()
        if (photo.isNotEmpty()) out.add(photo)
        if (photoOrig.isNotEmpty()) out.add(photoOrig)
        for (v in variants) for (s in v.steps) {
            if (s.hint.isNotEmpty()) out.add(s.hint)
            if (s.video.isNotEmpty()) { out.add(s.video); out.add(s.video + ".jpg") }
        }
        return out
    }

    companion object {
        fun fromJson(o: JSONObject): Recipe {
            val r = Recipe(o.getString("id"))
            r.title = o.optString("title"); r.dessert = o.optBoolean("dessert"); r.cls = o.optString("cls", "paladin")
            r.tier = o.optString("tier", "B"); r.effort = o.optInt("effort", 2); r.timeMin = o.optInt("time", 30)
            r.servings = Math.max(1, o.optInt("serv", 4)); r.photo = o.optString("photo"); r.photoOrig = o.optString("orig")
            r.filter = o.optString("filter"); r.strength = o.optDouble("str", 0.8).toFloat(); r.lastCooked = o.optLong("cooked")
            r.created = o.optLong("created"); r.updated = o.optLong("updated"); r.fame = o.optInt("fame")
            val eq = o.optJSONArray("equip"); if (eq != null) for (k in 0 until eq.length()) r.equipment.add(eq.getString(k))
            val v = o.optJSONArray("variants") ?: JSONArray()
            for (k in 0 until v.length()) r.variants.add(Variant.fromJson(v.getJSONObject(k)))
            if (r.variants.isEmpty()) r.variants.add(Variant("Classic"))
            return r
        }
    }
}

/**
 * A change one chef offers the other for a shared recipe.
 * kind "main": [data] is the whole recipe, replacing the shared one if accepted.
 * kind "variant": [data] is a Variant added to the shared recipe for everyone.
 * kind "special": [data] is a My Variant special the other chef can keep for themself.
 */
class Proposal(val id: String, val recipeId: String, val kind: String, val data: JSONObject, val from: String, val fromName: String, val at: Long, val title: String) {
    fun toJson(): JSONObject {
        val o = JSONObject(); o.put("id", id); o.put("r", recipeId); o.put("k", kind); o.put("d", data)
        o.put("from", from); o.put("fromName", fromName); o.put("at", at); o.put("title", title); return o
    }
    fun recipe(): Recipe? = if (kind == "main") try { Recipe.fromJson(data) } catch (e: Exception) { null } else null
    fun variant(): Variant? = if (kind != "main") try { Variant.fromJson(data) } catch (e: Exception) { null } else null
    fun photos(): List<String> {
        val r = recipe(); if (r != null) return r.photos()
        val out = ArrayList<String>()
        val v = variant() ?: return out
        for (st in v.steps) {
            if (st.hint.isNotEmpty()) out.add(st.hint)
            if (st.video.isNotEmpty()) { out.add(st.video); out.add(st.video + ".jpg") }
        }
        return out
    }
    companion object {
        fun fromJson(o: JSONObject) = Proposal(o.optString("id"), o.optString("r"), o.optString("k", "main"), o.optJSONObject("d") ?: JSONObject(),
            o.optString("from"), o.optString("fromName"), o.optLong("at"), o.optString("title"))
        fun newId(): String = UUID.randomUUID().toString().replace("-", "").substring(0, 12)
    }
}

class CookEntry(val recipeId: String, val variant: String, val at: Long, val fame: Int) {
    fun toJson(): JSONObject { val o = JSONObject(); o.put("r", recipeId); o.put("v", variant); o.put("at", at); o.put("f", fame); return o }
    companion object { fun fromJson(o: JSONObject) = CookEntry(o.optString("r"), o.optString("v"), o.optLong("at"), o.optInt("f")) }
}

class Chef(var id: String = UUID.randomUUID().toString()) {
    var name = "Chef"
    var race = "Halfling"
    var alignment = "Chaotic Hungry"
    var background = "Pâtissier"
    var portrait = 0
    var updated = 0L
    /** How many My Variant specials this chef has made (for the "First special" feat). */
    var specials = 0
    val history: MutableList<CookEntry> = ArrayList()

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id); o.put("name", name); o.put("race", race); o.put("align", alignment); o.put("bg", background)
        o.put("portrait", portrait); o.put("updated", updated); o.put("specials", specials)
        val h = JSONArray(); for (x in history) h.put(x.toJson()); o.put("history", h)
        return o
    }
    companion object {
        fun fromJson(o: JSONObject): Chef {
            val c = Chef(o.optString("id", UUID.randomUUID().toString()))
            c.name = o.optString("name", "Chef"); c.race = o.optString("race", "Halfling"); c.alignment = o.optString("align", "Chaotic Hungry")
            c.background = o.optString("bg", "Pâtissier"); c.portrait = o.optInt("portrait"); c.updated = o.optLong("updated"); c.specials = o.optInt("specials")
            val h = o.optJSONArray("history") ?: JSONArray()
            for (k in 0 until h.length()) c.history.add(CookEntry.fromJson(h.getJSONObject(k)))
            return c
        }
    }
}

/** Everything the app keeps, in one JSON file plus a photos folder. */
object Store {
    var loaded = false
    val recipes: MutableList<Recipe> = ArrayList()
    var me = Chef()
    var peer: Chef? = null
    /** Day key "2026-10-19" -> "recipeId|variantIndex". */
    val plan = HashMap<String, String>()
    var planUpdated = 0L
    /** Deleted recipe ids -> when, so a sync doesn't bring them back. */
    val tombstones = HashMap<String, Long>()
    var pairKey = ""
    var peerHost = ""
    var peerPort = 0
    /** This phone's own My Variant specials, per recipe id. Never synced as part of the cookbook. */
    val mine = HashMap<String, MutableList<Variant>>()
    /** Changes the other chef sent that wait for an answer here. */
    val inbox: MutableList<Proposal> = ArrayList()
    /** Changes sent from here that wait for the other chef's answer. */
    val outbox: MutableList<Proposal> = ArrayList()
    /** Proposal id -> "accepted" / "refused", told back to the other phone. */
    val answers = HashMap<String, String>()
    /** Proposal ids already received, so one isn't offered twice. */
    val handled = HashSet<String>()
    /** Recipe ids the other phone already has: edits to these become proposals. */
    val peerSeen = HashSet<String>()

    private fun file(c: Context) = File(c.filesDir, "praline.json")
    fun photosDir(c: Context): File { val d = File(c.filesDir, "photos"); d.mkdirs(); return d }
    fun photoFile(c: Context, name: String) = File(photosDir(c), name)
    fun newPhotoName(): String = UUID.randomUUID().toString().replace("-", "").substring(0, 16) + ".jpg"

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        clearAll()
        val f = file(c)
        if (!f.exists()) { me = Chef(); return }
        try {
            fromJson(JSONObject(f.readText()))
        } catch (e: Exception) {
            f.renameTo(File(c.filesDir, "praline.broken.json"))
            me = Chef()
        }
    }

    private fun clearAll() {
        recipes.clear(); plan.clear(); tombstones.clear(); peer = null
        mine.clear(); inbox.clear(); outbox.clear(); answers.clear(); handled.clear(); peerSeen.clear()
    }

    fun fromJson(o: JSONObject) {
        clearAll()
        val r = o.optJSONArray("recipes") ?: JSONArray()
        for (k in 0 until r.length()) recipes.add(Recipe.fromJson(r.getJSONObject(k)))
        me = if (o.has("me")) Chef.fromJson(o.getJSONObject("me")) else Chef()
        peer = if (o.has("peer")) Chef.fromJson(o.getJSONObject("peer")) else null
        val p = o.optJSONObject("plan") ?: JSONObject()
        val it = p.keys(); while (it.hasNext()) { val k = it.next(); plan[k] = p.getString(k) }
        planUpdated = o.optLong("planUpdated")
        val t = o.optJSONObject("tomb") ?: JSONObject()
        val it2 = t.keys(); while (it2.hasNext()) { val k = it2.next(); tombstones[k] = t.getLong(k) }
        pairKey = o.optString("pairKey"); peerHost = o.optString("peerHost"); peerPort = o.optInt("peerPort")
        val m = o.optJSONObject("mine") ?: JSONObject()
        val it3 = m.keys()
        while (it3.hasNext()) {
            val k = it3.next(); val arr = m.getJSONArray(k); val l = ArrayList<Variant>()
            for (i in 0 until arr.length()) l.add(Variant.fromJson(arr.getJSONObject(i)))
            mine[k] = l
        }
        val ib = o.optJSONArray("inbox") ?: JSONArray(); for (i in 0 until ib.length()) inbox.add(Proposal.fromJson(ib.getJSONObject(i)))
        val ob = o.optJSONArray("outbox") ?: JSONArray(); for (i in 0 until ob.length()) outbox.add(Proposal.fromJson(ob.getJSONObject(i)))
        val an = o.optJSONObject("answers") ?: JSONObject()
        val it4 = an.keys(); while (it4.hasNext()) { val k = it4.next(); answers[k] = an.getString(k) }
        val hd = o.optJSONArray("handled") ?: JSONArray(); for (i in 0 until hd.length()) handled.add(hd.getString(i))
        val ps = o.optJSONArray("peerSeen") ?: JSONArray(); for (i in 0 until ps.length()) peerSeen.add(ps.getString(i))
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        val r = JSONArray(); for (x in recipes) r.put(x.toJson()); o.put("recipes", r)
        o.put("me", me.toJson())
        val pe = peer; if (pe != null) o.put("peer", pe.toJson())
        val p = JSONObject(); for ((k, v) in plan) p.put(k, v); o.put("plan", p); o.put("planUpdated", planUpdated)
        val t = JSONObject(); for ((k, v) in tombstones) t.put(k, v); o.put("tomb", t)
        o.put("pairKey", pairKey); o.put("peerHost", peerHost); o.put("peerPort", peerPort)
        val m = JSONObject()
        for ((k, l) in mine) { val arr = JSONArray(); for (v in l) arr.put(v.toJson()); if (arr.length() > 0) m.put(k, arr) }
        o.put("mine", m)
        val ib = JSONArray(); for (x in inbox) ib.put(x.toJson()); o.put("inbox", ib)
        val ob = JSONArray(); for (x in outbox) ob.put(x.toJson()); o.put("outbox", ob)
        val an = JSONObject(); for ((k, v) in answers) an.put(k, v); o.put("answers", an)
        val hd = JSONArray(); for (x in handled) hd.put(x); o.put("handled", hd)
        val ps = JSONArray(); for (x in peerSeen) ps.put(x); o.put("peerSeen", ps)
        return o
    }

    fun save(c: Context) {
        val f = file(c)
        val tmp = File(c.filesDir, "praline.json.tmp")
        tmp.writeText(toJson().toString())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun recipe(id: String): Recipe? { for (r in recipes) if (r.id == id) return r; return null }

    fun upsert(c: Context, r: Recipe) {
        r.updated = System.currentTimeMillis()
        val i = recipes.indexOfFirst { it.id == r.id }
        if (i >= 0) recipes[i] = r else recipes.add(r)
        save(c)
    }

    fun delete(c: Context, r: Recipe) {
        recipes.removeAll { it.id == r.id }
        tombstones[r.id] = System.currentTimeMillis()
        val drop = ArrayList<String>()
        for ((k, v) in plan) if (v.startsWith(r.id + "|")) drop.add(k)
        for (k in drop) plan.remove(k)
        if (drop.isNotEmpty()) planUpdated = System.currentTimeMillis()
        save(c)
        cleanPhotos(c)
    }

    /** Removes photo files no recipe uses any more. */
    fun cleanPhotos(c: Context) {
        val used = HashSet<String>()
        for (r in recipes) used.addAll(r.photos())
        for ((_, l) in mine) for (v in l) for (st in v.steps) { used.add(st.hint); used.add(st.video); used.add(st.video + ".jpg") }
        for (p in inbox) used.addAll(p.photos())
        for (p in outbox) used.addAll(p.photos())
        val files = photosDir(c).listFiles() ?: return
        for (f in files) if (f.name !in used && System.currentTimeMillis() - f.lastModified() > 60_000L) f.delete()
    }

    /** Records a cook: fame for the chef, "last cooked" for the recipe. Returns the fame earned. */
    fun cook(c: Context, r: Recipe, variant: String, at: Long = System.currentTimeMillis()): Int {
        val fame = Fame.reward(r, me, at)
        me.history.add(CookEntry(r.id, variant, at, fame))
        me.updated = at
        r.lastCooked = at
        r.updated = at
        save(c)
        return fame
    }

    fun setPlan(c: Context, day: String, value: String?) {
        if (value == null) plan.remove(day) else plan[day] = value
        planUpdated = System.currentTimeMillis()
        save(c)
    }

    fun reset() { loaded = false; clearAll(); me = Chef(); pairKey = ""; peerHost = ""; peerPort = 0; planUpdated = 0L }
}

/** Quantities: parsing typed lines, scaling, and pretty fractions. */
object Qty {
    val UNITS = arrayOf("g", "kg", "mg", "ml", "cl", "dl", "l", "tsp", "tbsp", "cup", "cups", "oz", "lb", "lbs",
        "pinch", "pinches", "can", "cans", "clove", "cloves", "slice", "slices", "sheet", "sheets", "bunch", "handful",
        "cas", "cac", "càs", "càc", "c.à.s", "c.à.c", "pincée", "pincées", "boîte", "boîtes", "gousse", "gousses",
        "tranche", "tranches", "sachet", "sachets", "pot", "pots", "stick", "sticks", "knob", "drop", "drops")
    private val FRACS = mapOf('½' to 0.5, '⅓' to 1.0 / 3, '⅔' to 2.0 / 3, '¼' to 0.25, '¾' to 0.75, '⅛' to 0.125)

    fun parse(lineIn: String): Ingredient {
        val line = lineIn.trim()
        var i = 0
        var q = 0.0
        var has = false
        // whole number / decimal
        val num = StringBuilder()
        while (i < line.length && (Character.isDigit(line[i]) || ((line[i] == '.' || line[i] == ',') && num.isNotEmpty()))) { num.append(if (line[i] == ',') '.' else line[i]); i++ }
        if (num.isNotEmpty()) { q = num.toString().toDoubleOrNull() ?: 0.0; has = true }
        // "1/2" or "1 1/2"
        if (i < line.length && line[i] == '/' && has) {
            val den = StringBuilder(); var j = i + 1
            while (j < line.length && Character.isDigit(line[j])) { den.append(line[j]); j++ }
            val d = den.toString().toDoubleOrNull()
            if (d != null && d > 0) { q /= d; i = j }
        } else if (has && i + 1 < line.length && line[i] == ' ' && Character.isDigit(line[i + 1])) {
            var j = i + 1; val a = StringBuilder()
            while (j < line.length && Character.isDigit(line[j])) { a.append(line[j]); j++ }
            if (j < line.length && line[j] == '/') {
                val b = StringBuilder(); var k = j + 1
                while (k < line.length && Character.isDigit(line[k])) { b.append(line[k]); k++ }
                val d = b.toString().toDoubleOrNull()
                if (d != null && d > 0) { q += a.toString().toDouble() / d; i = k }
            }
        }
        // unicode fraction, alone or after a number
        if (has && i + 1 < line.length && line[i] == ' ' && FRACS.containsKey(line[i + 1])) i++
        if (i < line.length && FRACS.containsKey(line[i])) { q += FRACS[line[i]]!!; has = true; i++ }
        if (!has) return Ingredient(null, "", line)
        var rest = line.substring(i).trim()
        var unit = ""
        val word = rest.split(Regex("\\s+"), 2)
        if (word.isNotEmpty()) {
            val w = word[0].lowercase().trimEnd('.')
            if (UNITS.contains(w) && word.size > 1) { unit = word[0].trimEnd('.'); rest = word[1].trim() }
        }
        return Ingredient(q, unit, rest)
    }

    fun format(v: Double, unit: String): String {
        if (v <= 0) return "0"
        val u = unit.lowercase()
        val metric = u == "g" || u == "ml" || u == "mg"
        if (metric || v >= 20) {
            val r = when {
                v >= 100 -> Math.round(v / 5.0) * 5.0
                else -> Math.round(v).toDouble()
            }
            return r.toLong().toString()
        }
        val whole = Math.floor(v).toLong()
        val frac = v - whole
        val names = arrayOf(0.0 to "", 0.125 to "⅛", 0.25 to "¼", 1.0 / 3 to "⅓", 0.5 to "½", 2.0 / 3 to "⅔", 0.75 to "¾", 1.0 to "")
        var best = names[0]; var bestD = 9.0
        for (n in names) { val d = Math.abs(frac - n.first); if (d < bestD) { bestD = d; best = n } }
        if (bestD < 0.04) {
            val w = if (best.first == 1.0) whole + 1 else whole
            return if (best.second.isEmpty()) w.toString() else if (w == 0L) best.second else "$w${best.second}"
        }
        val one = Math.round(v * 10) / 10.0
        return if (one == Math.floor(one)) one.toLong().toString() else one.toString()
    }
}

/** Dates as plain day keys, and the Monday that starts a week. */
object Days {
    fun key(cal: Calendar): String = String.format("%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
    fun monday(offsetWeeks: Int = 0, now: Long = System.currentTimeMillis()): Calendar {
        val c = Calendar.getInstance(); c.timeInMillis = now
        c.set(Calendar.HOUR_OF_DAY, 12); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        val dow = c.get(Calendar.DAY_OF_WEEK) // Sunday=1
        val back = (dow + 5) % 7
        c.add(Calendar.DAY_OF_MONTH, -back + offsetWeeks * 7)
        return c
    }
    private val MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    fun short(ms: Long): String { val c = Calendar.getInstance(); c.timeInMillis = ms; return "${c.get(Calendar.DAY_OF_MONTH)} ${MONTHS[c.get(Calendar.MONTH)]}" }
    fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
        if (ms <= 0) return "never cooked"
        val days = ((startOfDay(now) - startOfDay(ms)) / 86_400_000L).toInt()
        return when {
            days <= 0 -> "cooked today"
            days == 1 -> "cooked yesterday"
            days < 7 -> "cooked $days days ago"
            days < 14 -> "cooked last week"
            else -> "cooked ${short(ms)}"
        }
    }
    fun startOfDay(ms: Long): Long { val c = Calendar.getInstance(); c.timeInMillis = ms; c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0); return c.timeInMillis }
    fun month(c: Calendar): String = MONTHS[c.get(Calendar.MONTH)]
}
