package com.yaya.praline

import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.MultiAutoCompleteTextView
import android.widget.TextView

/** Saving an edit: a My Variant special, or a shared recipe where the chef picks where the change goes. */
class SaveChoices(val ed: EditorScreen) {
    private val a get() = ed.a
    private val r get() = ed.r
    private val variant get() = ed.variant
    private val scroll get() = ed.scroll
    private fun refreshTabCounts() = ed.refreshTabCounts()
    private fun showTab(t: Int, keepFocus: Boolean = true) = ed.showTab(t, keepFocus)
    private fun reveal(v: View) = ed.reveal(v)
    private fun styleField(e: android.widget.EditText, hint: String, size: Float) = ed.styleField(e, hint, size)
    private fun watch(f: (String) -> Unit) = ed.watch(f)
    private val createdVariant get() = ed.createdVariant
    private val special get() = ed.special
    private fun names() = ed.names()

    fun special() {
        val live = Store.recipe(r.id) ?: run { a.pop(); return }
        val l = Share.specials(live.id)
        if (special < l.size) l[special] = r.variant(0)
        Store.save(a)
        a.pop()
        a.toast("Saved, just for you")
    }

    /** The edited variant compared with how it was. */
    private fun variantChanged(b: Recipe): Boolean {
        val v = r.variant(variant)
        if (createdVariant >= 0) return true
        var old: Variant? = null
        for (x in b.variants) if (x.name == v.name) old = x
        if (old == null) return true
        return old.toJson().toString() != v.toJson().toString()
    }

    /**
     * Someone else has this recipe too, so the chef picks where the change goes:
     * just for me (a My Variant), a new variant for everyone, or the main recipe for everyone.
     */
    fun choose(b: Recipe) {
        val peer = Share.peerName()
        val vName = r.variant(variant).name
        val s = Sheet(a, "Save it for…")
        s.body.addView(a.note("${b.title} is shared with $peer. Choose where this change goes; $peer can accept or refuse anything sent to them.", 12.5f))
        val changedVariant = variantChanged(b)
        fun option(icon: String, color: Int, title: String, sub: String, f: () -> Unit) {
            val c = a.card(color, 12, 10)
            val row = a.hbox()
            row.addView(a.icon(icon, 34, color), lp(a.dp(34), a.dp(34)).m(r = a.dp(12)))
            val col = a.vbox()
            col.addView(a.body(title, 15.5f, C.INK, 800))
            col.addView(a.note(sub, 11.5f))
            row.addView(col, lp(0, WRAP, 1f))
            c.addView(row)
            c.contentDescription = title
            c.onClick { s.dismiss(); f() }
            s.body.addView(c, lp(MATCH, WRAP).m(t = a.dp(10)))
        }
        if (changedVariant) {
            option("heart", C.RASP, "Just for me", "Saved as your own variant tab, only on this phone. ${b.title} stays as it is.") { saveForMe(b) }
            option("plus", C.PIST, "A new variant for everyone", "Sent to $peer as a new variant next to “${if (createdVariant >= 0) r.variant(0).name else vName}”.") { saveAsNewVariant(b) }
            if (createdVariant < 0) option("scroll", C.BUTTER, "Update the recipe for everyone", "The main recipe changes here, and $peer is asked to take the change.") { saveMainForEveryone() }
        } else {
            option("heart", C.RASP, "Just on this phone", "Your copy changes; $peer's stays as it is.") { Store.upsert(a, r); a.pop(); a.toast("Saved on this phone") }
            option("scroll", C.BUTTER, "Update it for everyone", "The recipe changes here, and $peer is asked to take the change.") { saveMainForEveryone() }
        }
        s.button("Keep editing")
        s.show()
    }

    /** The edited variant becomes a My Variant special; the shared recipe keeps its old variant. Other changes stay on this phone. */
    private fun saveForMe(b: Recipe) {
        val v = r.variant(variant)
        val mineV = v.copy(if (createdVariant >= 0) v.name else "My " + v.name)
        val kept = Recipe.fromJson(r.toJson())
        if (createdVariant >= 0) kept.variants.removeAt(createdVariant)
        else { var old: Variant? = null; for (x in b.variants) if (x.name == v.name) old = x; if (old != null) kept.variants[variant] = old.copy(old.name) }
        Store.upsert(a, kept)
        val k = Share.addSpecial(a, kept, mineV)
        a.pop()
        (a.top() as? RecipeScreen)?.showSpecial(k)
        a.toast("Saved as your variant, just for you")
    }

    private fun saveAsNewVariant(b: Recipe) {
        val v = r.variant(variant)
        val kept = Recipe.fromJson(r.toJson())
        val nv: Variant
        if (createdVariant >= 0) nv = v
        else {
            nv = v.copy(Share.uniqueName(names(), v.name + " · " + Store.me.name))
            var old: Variant? = null; for (x in b.variants) if (x.name == v.name) old = x
            if (old != null) kept.variants[variant] = old.copy(old.name)
            kept.variants.add(nv.copy(nv.name))
        }
        Store.upsert(a, kept)
        Share.proposeVariant(a, kept, nv)
        a.pop()
        (a.top() as? RecipeScreen)?.showVariant(nv.name)
        a.toast("Added, and sent to ${Share.peerName()}")
        Sync.nudge(a)
    }

    private fun saveMainForEveryone() {
        Store.upsert(a, r)
        Share.proposeMain(a, r)
        a.pop()
        a.toast("Saved, and sent to ${Share.peerName()} to accept")
        Sync.nudge(a)
    }
}
