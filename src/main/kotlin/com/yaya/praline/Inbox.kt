package com.yaya.praline

import android.view.Gravity
import android.view.View

/** A change the other chef sent: what it is, and Accept / Refuse. */
object Inbox {
    fun card(a: MainActivity, p: Proposal, after: () -> Unit): View {
        val c = a.card(C.MINT, 12, 10)
        val row = a.hbox(); row.gravity = Gravity.TOP
        val peer = Store.peer
        if (peer != null) row.addView(Medallion(a, peer.portrait, C.MINT), lp(a.dp(40), a.dp(40)).m(r = a.dp(10)))
        val col = a.vbox()
        val what = when (p.kind) { "variant" -> "a new variant for"; "special" -> "their special for"; else -> "a change to" }
        col.addView(a.label("${p.fromName} sent $what"))
        col.addView(a.script(p.title, 26f).single())
        col.addView(a.note(Share.describe(p), 12f))
        row.addView(col, lp(0, WRAP, 1f))
        c.addView(row)
        val btns = a.hbox(); btns.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        val no = a.chip("Refuse", false, C.BEIGE, "x", 13f)
        no.contentDescription = "refuse ${p.title}"
        no.onClick { Share.refuse(a, p); a.toast("Kept as it was"); Sync.nudge(a); after() }
        val yes = a.chip(if (p.kind == "main") "Accept the change" else "Accept", true, C.PIST, "check", 13f)
        yes.contentDescription = "accept ${p.title}"
        yes.onClick {
            Share.accept(a, p)
            a.toast(when (p.kind) { "variant" -> "New variant added"; "special" -> "Added as your own variant"; else -> "Recipe updated" })
            Sync.nudge(a); after()
        }
        btns.addView(no, lp(WRAP, WRAP).m(r = a.dp(8))); btns.addView(yes)
        c.addView(btns, lp(MATCH, WRAP).m(t = a.dp(8)))
        return c
    }
}
