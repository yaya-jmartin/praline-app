package com.yaya.praline

import android.view.Gravity
import android.view.View

/** The time's-up card that floats over every page while a timer rings, then says when it finished. */
class TimeBanner(val a: MainActivity) {
    private var banner: View? = null

    fun update() {
        val t = a.top()
        var ringing: String? = null
        for (id in Timers.active(a)) if (Timers.state(a, id) == Timers.DONE && t?.ownsTimer(id) != true) { ringing = id; break }
        var done: Timers.Stopped? = null
        if (ringing == null) for (x in Timers.stoppedCards(a)) if (t?.ownsTimer(x.id) != true) done = x
        val key = if (ringing != null) "ring:$ringing" else if (done != null) "done:${done.id}:${done.at}" else null
        if (key == null) { banner?.let { a.host.removeView(it) }; banner = null; return }
        if (banner != null && banner!!.tag == key) return
        banner?.let { a.host.removeView(it) }
        val card = if (ringing != null) ringCard(ringing) else doneCard(done!!)
        card.tag = key
        val lpb = flp(MATCH, WRAP, Gravity.TOP).m(a.dp(12), a.insetTop + a.dp(8), a.dp(12), 0)
        a.host.addView(card, lpb)
        card.translationY = -a.dp(120).toFloat()
        card.animate().translationY(0f).setDuration(260).start()
        banner = card
    }

    private fun ringCard(id: String): View {
        val card = a.card(C.INK, 14, 10, C.RASP)
        val row = a.hbox()
        row.addView(a.icon("hour", 28, C.BUTTER, C.INK, 7f), lp(a.dp(28), a.dp(28)).m(r = a.dp(10)))
        val col = a.vbox()
        col.addView(a.label("Time's up", C.WHITE))
        col.addView(a.script(Timers.label(a, id).ifEmpty { "Timer" }, 26f, C.WHITE).single())
        row.addView(col, lp(0, WRAP, 1f))
        val plus = a.chip("+1 min"); plus.onClick { Timers.adjust(a, id, 60_000L); update() }
        val stop = a.chip("Stop", true, C.BUTTER); stop.onClick { Timers.stop(a, id); update() }
        row.addView(plus, lp(WRAP, WRAP).m(r = a.dp(6))); row.addView(stop)
        card.addView(row)
        return card
    }

    /** After Stop the card stays, quieter, saying when it finished, until it is closed. */
    private fun doneCard(d: Timers.Stopped): View {
        val card = a.card(C.PIST, 14, 10, C.TIMER_BG)
        val row = a.hbox()
        row.addView(a.icon("check", 26, 0, C.TIMER, 10f), lp(a.dp(26), a.dp(26)).m(r = a.dp(10)))
        val col = a.vbox()
        col.addView(a.label("Done at " + Timers.clock(d.at), C.TIMER_SOFT))
        col.addView(a.script(d.label.ifEmpty { "Timer" }, 24f, C.WHITE).single())
        row.addView(col, lp(0, WRAP, 1f))
        val x = a.roundIcon("x", 34, C.CARD, 13); x.contentDescription = "close done card"
        x.onClick { Timers.dismissStopped(a, d.id); update() }
        row.addView(x, lp(a.dp(34), a.dp(34)))
        card.addView(row)
        return card
    }

    fun toFront() { banner?.bringToFront(); Sheet.open?.overlay?.bringToFront() }
}
