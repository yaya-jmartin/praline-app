package com.yaya.praline

import org.json.JSONObject
import java.io.File

/**
 * When Android closes Praliné in the background (to free memory), the open recipe draft and the quest
 * step come back where they were. The top page's state is written on every pause and read once at launch.
 */
object Resume {
    private fun file(a: MainActivity) = File(a.filesDir, "resume.json")

    fun save(a: MainActivity) {
        val f = file(a)
        val s = try { a.top()?.saveState() } catch (e: Exception) { null }
        try { if (s == null) f.delete() else { val t = File(a.filesDir, "resume.json.tmp"); t.writeText(s.toString()); t.renameTo(f) } } catch (e: Exception) { }
    }

    /** [killed]: Android is rebuilding the app it closed. A fresh launch drops whatever was left. */
    fun restore(a: MainActivity, killed: Boolean) {
        val f = file(a)
        if (!f.exists()) return
        val o = try { JSONObject(f.readText()) } catch (e: Exception) { null }
        f.delete()
        if (!killed || o == null) return
        open(a, o)
    }

    /** Rebuilds the pages for a saved state (recipe page underneath, then the editor or the quest). */
    fun open(a: MainActivity, o: JSONObject) {
        when (o.optString("kind")) {
            "quest" -> {
                val r = Store.recipe(o.optString("r")) ?: return
                a.push(RecipeScreen(a, r), animate = false)
                a.push(QuestScreen(a, r, o.optInt("variant"), o.optDouble("scale", 1.0), o.optInt("step")), animate = false)
            }
            "editor" -> {
                val d = o.optJSONObject("draft") ?: return
                val draft = try { Recipe.fromJson(d) } catch (e: Exception) { return }
                val original = if (o.optBoolean("new")) null else Store.recipe(draft.id)
                if (original != null) a.push(RecipeScreen(a, original), animate = false)
                val e = EditorScreen(a, original, o.optInt("variant"), if (original != null) o.optInt("special", -1) else -1, null, draft, o.optInt("created", -1))
                e.tab = o.optInt("tab")
                a.push(e, animate = false)
            }
        }
    }
}
