package com.yaya.praline

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/** The chef's character sheet. The other phone's chef opens it read-only. */
class ProfileScreen(a: MainActivity, var chef: Chef, val readOnly: Boolean) : Screen(a) {
    private var shown = false

    override fun build(): View {
        val sv = ScrollView(a); sv.isVerticalScrollBarEnabled = false
        val col = a.vbox()
        col.setPadding(a.dp(22), a.dp(6), a.dp(22), a.dp(40))
        sv.addView(col)
        val recipes = Store.recipes

        val top = a.hbox()
        val back = a.roundIcon("back", 38, C.CARD, 16); back.onClick { a.pop() }
        top.addView(back, lp(a.dp(38), a.dp(38)))
        top.addView(a.label(if (readOnly) "The other chef · read only" else "Character sheet").also { it.gravity = Gravity.CENTER }, lp(0, WRAP, 1f))
        if (!readOnly) {
            val ed = a.roundIcon("pencil", 38, C.CARD, 16, 10f); ed.contentDescription = "edit chef"; ed.onClick { edit() }
            top.addView(ed, lp(a.dp(38), a.dp(38)))
        } else top.addView(a.space(a.dp(38), 1))
        col.addView(top)

        // portrait + ribbon
        val head = a.vbox(); head.gravity = Gravity.CENTER_HORIZONTAL
        val pw = a.resources.displayMetrics.widthPixels - a.dp(44)
        head.addView(ArchPortrait(a, chef.portrait), lp(MATCH, (pw * 0.74f).toInt()).m(t = a.dp(8)))
        head.addView(Ribbon(a, chef.name.ifEmpty { "Chef" }), lp(a.dp(240), a.dp(60)).m(t = -a.dp(28)))
        val fame = Fame.total(chef); val lvl = Fame.level(fame)
        head.addView(a.body("Level $lvl · ${chef.race} ${Fame.classTitle(chef, recipes)}", 15f, C.INK, -1).also { it.typeface = F.italic(a); it.gravity = Gravity.CENTER })
        val distinct = HashSet<String>(); for (e in chef.history) distinct.add(e.recipeId)
        val ac = Math.min(20, 10 + distinct.size / 3)
        head.addView(a.note("Background ${chef.background} · Alignment ${chef.alignment} · AC $ac (oven mitts)", 11.5f).also { it.gravity = Gravity.CENTER }, lp(MATCH, WRAP).m(t = a.dp(2)))
        head.addView(a.note(Fame.rankTitle(lvl), 12f, C.RASP).also { it.gravity = Gravity.CENTER }, lp(MATCH, WRAP).m(t = a.dp(2)))
        col.addView(head)

        // fame
        val prev = Fame.threshold(lvl); val next = Fame.threshold(lvl + 1)
        val fl = a.hbox()
        fl.addView(a.label("Fame", C.INK), lp(0, WRAP, 1f))
        fl.addView(a.label("$fame · ${next - fame} to level ${lvl + 1}"))
        col.addView(fl, lp(MATCH, WRAP).m(t = a.dp(14)))
        col.addView(FameBar(a, (fame - prev).toFloat() / Math.max(1, next - prev)), lp(MATCH, a.dp(14)).m(t = a.dp(4)))

        // weapon + stats
        val row = a.hbox(); row.gravity = Gravity.TOP
        row.addView(weaponCard(recipes), lp(0, WRAP, 1.05f).m(r = a.dp(12)))
        row.addView(statsGrid(recipes), lp(0, WRAP, 1f))
        col.addView(row, lp(MATCH, WRAP).m(t = a.dp(16)))

        // feats
        col.addView(a.label("Feats"), lp(MATCH, WRAP).m(t = a.dp(18)))
        val feats = FlowLayout(a, a.dp(10), a.dp(10))
        for (f in Fame.feats(chef, recipes)) {
            val c = a.vbox(); c.gravity = Gravity.CENTER_HORIZONTAL
            c.addView(a.roundIcon(f.icon, 46, if (f.earned) f.color else C.CARD, 22, 8f), lp(a.dp(46), a.dp(46)))
            c.addView(a.body(f.title, 10f, C.INK, 600).also { it.gravity = Gravity.CENTER; it.maxWidth = a.dp(70) }, lp(WRAP, WRAP).m(t = a.dp(3)))
            c.alpha = if (f.earned) 1f else 0.35f
            c.minimumWidth = a.dp(70)
            feats.addView(c)
        }
        col.addView(feats, lp(MATCH, WRAP).m(t = a.dp(6)))

        // totals
        val tot = a.hbox()
        var tierS = 0; for (r in recipes) if (r.tier == "S") tierS++
        tot.addView(total(recipes.size, "recipes"), lp(0, WRAP, 1f))
        tot.addView(total(chef.history.size, "times cooked"), lp(0, WRAP, 1f))
        tot.addView(total(tierS, "Tier S"), lp(0, WRAP, 1f))
        col.addView(tot, lp(MATCH, WRAP).m(t = a.dp(18)))

        // quest log
        col.addView(a.script("Quest log", 34f), lp(MATCH, WRAP).m(t = a.dp(14)))
        if (chef.history.isEmpty()) col.addView(a.note("No quests yet. Cook a recipe to the end to earn fame.", 13f))
        val log = chef.history.sortedByDescending { it.at }
        for (k in 0 until Math.min(12, log.size)) {
            val e = log[k]
            val r = Store.recipe(e.recipeId)
            val l = a.hbox(); l.setPadding(0, a.dp(5), 0, a.dp(5))
            val cls = Classes.get(r?.cls ?: "paladin")
            l.addView(a.icon(cls.icon, 22, cls.color, C.INK, 7f), lp(a.dp(22), a.dp(22)).m(r = a.dp(10)))
            val t = a.vbox()
            t.addView(a.body(r?.title ?: "A forgotten recipe", 14f, C.INK, 600).single())
            t.addView(a.note(e.variant + " · " + Days.ago(e.at), 11f))
            l.addView(t, lp(0, WRAP, 1f))
            l.addView(a.body("+${e.fame}", 13f, C.RASP, 800))
            if (r != null) l.onClick { a.push(RecipeScreen(a, r)) }
            col.addView(l)
        }

        if (!readOnly) party(col)
        else col.addView(a.note("This chef lives on the other phone. Their sheet updates each time your phones sync.", 12f), lp(MATCH, WRAP).m(t = a.dp(18)))
        return sv
    }

    private fun total(n: Int, what: String): View {
        val c = a.vbox(); c.gravity = Gravity.CENTER_HORIZONTAL
        c.addView(a.body(n.toString(), 20f, C.INK, 800).also { it.gravity = Gravity.CENTER })
        c.addView(a.body(what, 11f, C.INK2, 600).also { it.gravity = Gravity.CENTER })
        return c
    }

    private fun weaponCard(recipes: List<Recipe>): View {
        val card = a.card(C.BUTTER, 12, 10)
        card.addView(a.label("Favourite weapon"))
        val ws = Fame.weapons(chef, recipes)
        val best = if (ws.isNotEmpty() && ws[0].second > 0) ws[0].first else "spoon"
        val r = a.hbox()
        r.addView(a.icon(best, 42, C.BUTTER), lp(a.dp(42), a.dp(42)).m(r = a.dp(8)))
        val t = a.vbox()
        t.addView(a.script(Fame.weaponName(best), 28f).single())
        val n = if (ws.isNotEmpty()) ws[0].second else 0
        t.addView(a.note(if (n > 0) "used in $n quest" + (if (n == 1) "" else "s") else "every chef starts here", 10.5f))
        r.addView(t, lp(0, WRAP, 1f))
        card.addView(r, lp(MATCH, WRAP).m(t = a.dp(4)))
        val others = a.hbox()
        for (k in 1 until Math.min(5, ws.size)) {
            val c = a.vbox(); c.gravity = Gravity.CENTER_HORIZONTAL
            c.addView(a.icon(ws[k].first, 24, 0, C.INK, 7f), lp(a.dp(24), a.dp(24)))
            c.addView(a.body(ws[k].second.toString(), 10f, C.INK, 600).also { it.gravity = Gravity.CENTER })
            others.addView(c, lp(0, WRAP, 1f))
        }
        card.addView(others, lp(MATCH, WRAP).m(t = a.dp(6)))
        return card
    }

    private fun statsGrid(recipes: List<Recipe>): View {
        val g = a.vbox()
        val st = Fame.stats(chef, recipes)
        var i = 0
        while (i < st.size) {
            val row = a.hbox()
            for (k in 0..1) {
                if (i + k >= st.size) break
                val s = st[i + k]
                val c = a.vbox(); c.gravity = Gravity.CENTER_HORIZONTAL
                c.background = InkBox(a, C.CARD, s.color, 12f, 1.8f, 3f, i + k)
                c.setPadding(0, a.dp(3), a.dp(3), a.dp(6))
                c.addView(a.body(s.key, 8.5f, C.INK2, 700).also { it.letterSpacing = 0.1f; it.gravity = Gravity.CENTER })
                c.addView(a.body(s.score.toString(), 19f, C.INK, 800).also { it.gravity = Gravity.CENTER })
                c.addView(a.body(s.modText, 10.5f, C.INK, 600).also { it.gravity = Gravity.CENTER })
                row.addView(c, lp(0, WRAP, 1f).m(r = if (k == 0) a.dp(7) else 0))
            }
            g.addView(row, lp(MATCH, WRAP).m(b = a.dp(7)))
            i += 2
        }
        return g
    }

    private fun party(col: LinearLayout) {
        col.addView(a.script("Party", 34f), lp(MATCH, WRAP).m(t = a.dp(18)))
        val party = Store.party()
        if (party.isEmpty()) col.addView(a.note("Pair another phone and you'll share one cookbook, each with your own chef. Up to four chefs.", 12.5f))
        for (ch in party) {
            val card = a.card(C.MINT, 12, 10)
            val r = a.hbox()
            r.addView(Medallion(a, ch.portrait, C.MINT), lp(a.dp(54), a.dp(54)).m(r = a.dp(10)))
            val t = a.vbox()
            t.addView(a.script(ch.name, 28f).single())
            val peer = Store.peers.firstOrNull { it.chefId == ch.id }
            val via = if (peer == null) " · met through the party" else ""
            t.addView(a.note("Level ${Fame.level(Fame.total(ch))} · ${Fame.classTitle(ch, Store.recipes)}$via", 11.5f))
            r.addView(t, lp(0, WRAP, 1f))
            r.addView(a.icon("chev", 18, 0, C.INK, 9f), lp(a.dp(18), a.dp(18)))
            card.addView(r)
            card.onClick { a.push(ProfileScreen(a, ch, true)) }
            col.addView(card, lp(MATCH, WRAP).m(t = a.dp(8)))
            if (peer != null) {
                val btns = a.hbox()
                val sync = a.boxButton("Sync now", true, C.MINT)
                sync.contentDescription = "sync ${ch.name}"
                sync.onClick {
                    a.toast("Looking for ${ch.name}'s phone…")
                    Sync.syncPeer(a, peer) { err ->
                        if (err == Sync.NO_ANSWER) Sync.autoSync(a) { p, e2 -> if (p === peer) a.toast(if (e2 == null) "Synced with ${ch.name}" else Sync.explain(e2, ch.name)) }
                        else a.toast(if (err == null) "Synced with ${ch.name}" else Sync.explain(err, ch.name))
                    }
                }
                btns.addView(sync, lp(0, WRAP, 1f).m(r = a.dp(10)))
                val un = a.boxButton("Unpair")
                un.contentDescription = "unpair ${ch.name}"
                un.onClick {
                    Sheet(a, "Unpair ${ch.name}?").also {
                        it.body.addView(a.note("Your recipes stay on this phone. This phone and ${ch.name}'s stop syncing until you pair again.", 13f))
                    }.button("Keep").button("Unpair", primary = true) { Sync.unpair(a, peer); rebuild() }.show()
                }
                btns.addView(un, lp(0, WRAP, 1f))
                col.addView(btns, lp(MATCH, WRAP).m(t = a.dp(8)))
            }
        }
        if (Store.peers.size < Store.PARTY_MAX - 1 && party.size < Store.PARTY_MAX - 1) {
            val b = a.bigButton(if (party.isEmpty()) "Invite a chef" else "Invite another chef", C.MINT, C.INK, C.INK, "qr")
            b.contentDescription = "Invite a chef"
            b.onClick { a.push(PairScreen(a)) }
            col.addView(b, lp(MATCH, WRAP).m(t = a.dp(12)))
        }
        if (party.isNotEmpty()) col.addView(a.note("Phones sync by themselves every few seconds while Praliné is open on each, " + (if (HomeWifi.locked(a)) "on your home Wi-Fi." else "on the same Wi-Fi."), 11.5f), lp(MATCH, WRAP).m(t = a.dp(8)))
    }

    private fun edit() {
        val s = Sheet(a, "Your chef")
        val name = Fields.line(a, "Name"); name.setText(chef.name)
        val race = Fields.line(a, "Race"); race.setText(chef.race)
        val al = Fields.line(a, "Alignment"); al.setText(chef.alignment)
        val bg = Fields.line(a, "Background"); bg.setText(chef.background)
        s.body.addView(a.label("Name")); s.body.addView(name, lp(MATCH, WRAP).m(t = a.dp(3), b = a.dp(8)))
        s.body.addView(a.label("Race")); s.body.addView(race, lp(MATCH, WRAP).m(t = a.dp(3), b = a.dp(8)))
        s.body.addView(a.label("Alignment")); s.body.addView(al, lp(MATCH, WRAP).m(t = a.dp(3), b = a.dp(8)))
        s.body.addView(a.label("Background")); s.body.addView(bg, lp(MATCH, WRAP).m(t = a.dp(3), b = a.dp(8)))
        s.body.addView(a.label("Portrait"))
        var pick = chef.portrait
        val lvl = Fame.level(Fame.total(chef))
        val grid = a.vbox()
        val meds = ArrayList<Medallion>()
        var row = a.hbox()
        for (i in 0 until Portraits.COUNT) {
            if (i % 5 == 0) { row = a.hbox(); row.gravity = Gravity.TOP; grid.addView(row, lp(MATCH, WRAP).m(b = a.dp(8))) }
            val open = Portraits.unlocked(i, lvl)
            val cell = a.vbox(); cell.gravity = Gravity.CENTER_HORIZONTAL
            val m = Medallion(a, i, C.CARD)
            m.locked = !open
            m.picked = i == pick
            meds.add(m)
            cell.addView(m, lp(a.dp(54), a.dp(54)))
            cell.addView(a.body(if (open) Portraits.NAMES[i] else "Lv ${Portraits.UNLOCK[i]}", 9.5f, if (open) C.INK2 else C.RASP, 600).also { it.gravity = Gravity.CENTER; it.maxLines = 2 }, lp(MATCH, WRAP).m(t = a.dp(2)))
            cell.contentDescription = "portrait $i"
            cell.onClick {
                if (!open) a.toast("Reach level ${Portraits.UNLOCK[i]} to unlock the ${Portraits.NAMES[i]}")
                else { pick = i; for (k in meds.indices) { meds[k].picked = k == i; meds[k].invalidate() } }
            }
            row.addView(cell, lp(0, WRAP, 1f))
        }
        while (row.childCount < 5) row.addView(a.space(), lp(0, 1, 1f))
        s.body.addView(grid, lp(MATCH, WRAP).m(t = a.dp(6)))
        s.body.addView(a.note("New outfits and scenes unlock as your chef levels up.", 11f), lp(MATCH, WRAP).m(t = a.dp(2)))
        s.button("Cancel").button("Save", primary = true) {
            chef.name = name.text.toString().trim().ifEmpty { "Chef" }
            chef.race = race.text.toString().trim().ifEmpty { "Halfling" }
            chef.alignment = al.text.toString().trim().ifEmpty { "Chaotic Hungry" }
            chef.background = bg.text.toString().trim().ifEmpty { "Pâtissier" }
            chef.portrait = pick
            chef.updated = System.currentTimeMillis()
            Store.save(a)
            a.hideKeyboard()
            rebuild()
        }
        s.show()
    }

    override fun onShow() {
        if (readOnly) { val p = Store.chefs[chef.id]; if (p != null) chef = p } else chef = Store.me
        if (shown) rebuild()
        shown = true
    }

    override fun onSynced() { onShow() }
}
