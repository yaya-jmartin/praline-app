package com.yaya.praline

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/** Mission invites (1.4): one chef sets off and invites the others; each accepts or declines on their own phone. */
object MissionInvite {
    /** The card on the home page and the mission page: who invites, to which world, Accept / Decline. */
    fun card(a: MainActivity, w: Missions.World, after: () -> Unit): View {
        val p = Missions.party(w)
        val by = if (p != null) Store.chef(p.by) else null
        val c = a.card(C.TANG, 12, 10)
        val row = a.hbox(); row.gravity = Gravity.TOP
        if (by != null) row.addView(Medallion(a, by.portrait, C.TANG), lp(a.dp(40), a.dp(40)).m(r = a.dp(10)))
        else row.addView(a.icon("map", 40, C.TANG), lp(a.dp(40), a.dp(40)).m(r = a.dp(10)))
        val col = a.vbox()
        col.addView(a.label("${by?.name ?: "Your party"} invites you on a mission"))
        col.addView(a.script(w.name, 26f).single())
        val n = (p?.members?.size ?: 1) + 1
        col.addView(a.note("Your cooks count from the moment you join. With $n chefs, the spots ahead sit $n× further along the trail.", 12f))
        row.addView(col, lp(0, WRAP, 1f))
        c.addView(row)
        val btns = a.hbox(); btns.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        val no = a.chip("Decline", false, C.BEIGE, "x", 13f)
        no.contentDescription = "decline mission"
        no.onClick { Missions.decline(a, w); Sync.nudge(a); after() }
        val yes = a.chip("Join the party", true, C.PIST, "check", 13f)
        yes.contentDescription = "join mission"
        yes.onClick {
            if (Missions.accept(a, w)) { a.toast("You joined the party"); Sync.nudge(a) } else a.toast("That party already has four chefs")
            after()
        }
        btns.addView(no, lp(WRAP, WRAP).m(r = a.dp(8))); btns.addView(yes)
        c.addView(btns, lp(MATCH, WRAP).m(t = a.dp(8)))
        return c
    }

    /** Before the first step: pick who to invite, then set off. */
    fun setOff(a: MainActivity, col: LinearLayout, w: Missions.World, after: () -> Unit) {
        val c = a.card(C.BUTTER, 12, 12)
        c.addView(a.script("Gather your party", 28f))
        val others = Store.party()
        c.addView(a.note(if (others.isEmpty()) "Set off alone, or pair other phones first: up to four chefs walk together. Each spot sits further along the trail for each chef in the party."
            else "Pick who to invite. They join from their own phone; each spot sits further along the trail for each chef in the party.", 12f))
        val picked = LinkedHashSet<String>()
        for (ch in others) picked.add(ch.id)
        val fl = FlowLayout(a, a.dp(7), a.dp(8))
        fun paint() {
            fl.removeAllViews()
            for (ch in others.take(Store.PARTY_MAX - 1)) {
                val on = picked.contains(ch.id)
                val chip = a.chip(ch.name, on, C.MINT, if (on) "check" else "plus", 13f)
                chip.contentDescription = "invite ${ch.name}"
                chip.onClick { if (!picked.remove(ch.id)) picked.add(ch.id); paint() }
                fl.addView(chip)
            }
        }
        paint()
        if (others.isNotEmpty()) c.addView(fl, lp(MATCH, WRAP).m(t = a.dp(8)))
        val go = a.bigButton(if (picked.isEmpty() && others.isEmpty()) "Set off" else "Set off and invite", C.RASP, C.WHITE, C.INK, "map")
        go.contentDescription = "set off"
        go.onClick {
            Missions.start(a, w, invite = picked)
            Sync.nudge(a)
            after()
        }
        c.addView(go, lp(MATCH, WRAP).m(t = a.dp(10)))
        col.addView(c, lp(MATCH, WRAP).m(t = a.dp(8)))
    }

    /** The party under way: who walks, who is invited, and room to invite more. */
    fun members(a: MainActivity, col: LinearLayout, w: Missions.World, p: Missions.Progress, after: () -> Unit) {
        val party = Missions.party(w) ?: return
        val row = a.hbox(); row.gravity = Gravity.CENTER_VERTICAL
        for (ch in Missions.members(w)) {
            val m = Medallion(a, ch.portrait, if (ch === Store.me) C.LILAC else C.MINT)
            m.contentDescription = "member ${ch.name}"
            row.addView(m, lp(a.dp(30), a.dp(30)).m(r = a.dp(4)))
        }
        val waiting = party.invited.keys.filter { it !in party.members && it !in party.declined }
        val words = "Party of ${p.size}" + (if (waiting.isNotEmpty()) " · ${waiting.size} invited" else "")
        row.addView(a.body(words, 12f, C.INK, 600), lp(0, WRAP, 1f).m(l = a.dp(6)))
        val canInvite = Store.party().filter { it.id !in party.members && (it.id !in party.invited || it.id in party.declined) }
        if (Missions.joined(w) && canInvite.isNotEmpty() && party.members.size + waiting.size < Store.PARTY_MAX) {
            val inv = a.chip("Invite", false, C.MINT, "plus", 12f)
            inv.contentDescription = "invite to mission"
            inv.onClick {
                val s = Sheet(a, "Invite to the party")
                s.body.addView(a.note("The spots not reached yet stretch when they join.", 12f))
                for (ch in canInvite) {
                    val chip = a.chip(ch.name, false, C.MINT, "plus", 13f)
                    chip.onClick { s.dismiss(); Missions.invite(a, w, listOf(ch.id)); Sync.nudge(a); a.toast("${ch.name} is invited"); after() }
                    s.body.addView(chip, lp(WRAP, WRAP).m(t = a.dp(8)))
                }
                s.button("Close").show()
            }
            row.addView(inv)
        }
        col.addView(row, lp(MATCH, WRAP).m(t = a.dp(6)))
    }
}
