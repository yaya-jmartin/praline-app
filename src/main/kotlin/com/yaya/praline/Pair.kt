package com.yaya.praline

import android.Manifest
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView

/** Pairing a second phone: one shows its QR code, the other scans it. Same Wi-Fi, no account. */
class PairScreen(a: MainActivity) : Screen(a) {
    var mode = 0          // 0 choose, 1 show my code, 2 scan, 3 paired, 4 type the code
    override val name: String get() = when (mode) { 1 -> "Pair · my code"; 2 -> "Pair · scan"; 3 -> "Pair · done"; 4 -> "Pair · type code"; else -> "Pair" }
    private var busy = false
    private var scanner: QrScanner? = null
    private var error = ""

    override fun build(): View {
        scanner = null
        val sv = ScrollView(a); sv.isVerticalScrollBarEnabled = false
        val col = a.vbox()
        col.setPadding(a.dp(22), a.dp(6), a.dp(22), a.dp(30))
        sv.addView(col)
        val top = a.hbox()
        val back = a.roundIcon("back", 38, C.CARD, 16); back.onClick { if (mode == 1 || mode == 2 || mode == 4) { mode = 0; error = ""; rebuild() } else a.pop() }
        top.addView(back, lp(a.dp(38), a.dp(38)).m(r = a.dp(12)))
        top.addView(a.label("Party of two"))
        col.addView(top)
        col.addView(a.script("Invite a chef", 48f), lp(MATCH, WRAP).m(l = -a.dp(4)))

        // pairing only happens on the home Wi-Fi
        if (mode != 3 && !HomeWifi.here(a)) { homeFirst(col); return sv }
        when (mode) {
            0 -> choose(col)
            1 -> showCode(col)
            2 -> scan(col)
            4 -> typeCode(col)
            else -> paired(col)
        }
        return sv
    }

    /** Before pairing: this Wi-Fi has to be the saved home one. */
    private fun homeFirst(col: android.widget.LinearLayout) {
        val home = HomeWifi.saved(a)
        val card = a.card(C.TANG, 14, 14)
        card.addView(a.script("Home dungeon access", 30f))
        card.addView(a.note(if (home.isEmpty()) "Praliné pairs and syncs only on your home Wi-Fi. Are you home? Save this Wi-Fi as home first."
            else "You're not on your home Wi-Fi (“$home”), so the phones won't pair or sync here.", 12.5f), lp(MATCH, WRAP).m(t = a.dp(2)))
        val b = a.chip(if (home.isEmpty()) "Use this Wi-Fi as home" else "Make this Wi-Fi home instead", true, C.TANG, "wifi", 13f)
        b.contentDescription = "set home wifi"
        b.onClick { HomeWifi.ask(a) { rebuild() } }
        card.addView(b, lp(WRAP, WRAP).m(t = a.dp(10)))
        col.addView(card, lp(MATCH, WRAP).m(t = a.dp(6)))
    }

    private fun choose(col: android.widget.LinearLayout) {
        col.addView(a.note("Both phones need Praliné open and the same home Wi-Fi. Pair once; after that they sync on their own whenever both are open. Each phone keeps its own chef, and you share one cookbook.", 13f), lp(MATCH, WRAP).m(b = a.dp(16)))
        val show = a.card(C.LILAC, 14, 14)
        val r1 = a.hbox()
        r1.addView(a.icon("qr", 44, C.LILAC), lp(a.dp(44), a.dp(44)).m(r = a.dp(12)))
        val t1 = a.vbox()
        t1.addView(a.script("Show my code", 30f))
        t1.addView(a.note("The other phone scans it", 12f))
        r1.addView(t1, lp(0, WRAP, 1f))
        show.addView(r1)
        show.onClick { mode = 1; rebuild() }
        col.addView(show, lp(MATCH, WRAP).m(b = a.dp(14)))
        val scan = a.card(C.MINT, 14, 14)
        val r2 = a.hbox()
        r2.addView(a.icon("cam", 44, C.MINT), lp(a.dp(44), a.dp(44)).m(r = a.dp(12)))
        val t2 = a.vbox()
        t2.addView(a.script("Scan their code", 30f))
        t2.addView(a.note("Point the camera at the other phone", 12f))
        r2.addView(t2, lp(0, WRAP, 1f))
        scan.addView(r2)
        scan.onClick { a.withPermission(Manifest.permission.CAMERA) { ok -> if (ok) { mode = 2; rebuild() } else { mode = 4; rebuild() } } }
        col.addView(scan, lp(MATCH, WRAP))
        val type = a.card(C.BUTTER, 14, 14)
        val r3 = a.hbox()
        r3.addView(a.icon("pencil", 44, C.BUTTER), lp(a.dp(44), a.dp(44)).m(r = a.dp(12)))
        val t3 = a.vbox()
        t3.addView(a.script("Type their code", 30f))
        t3.addView(a.note("For a camera that won't scan", 12f))
        r3.addView(t3, lp(0, WRAP, 1f))
        type.addView(r3)
        type.onClick { mode = 4; rebuild() }
        col.addView(type, lp(MATCH, WRAP).m(t = a.dp(14)))
    }

    private fun showCode(col: android.widget.LinearLayout) {
        Sync.startServer(a)
        val ip = Sync.localIp()
        if (ip.isEmpty()) {
            col.addView(a.note("This phone isn't on a Wi-Fi network. Connect both phones to the same Wi-Fi and try again.", 14f, C.RASP))
            return
        }
        val card = a.card(C.LILAC, 16, 16); card.gravity = Gravity.CENTER_HORIZONTAL
        val img = ImageView(a)
        val size = Math.min(a.resources.displayMetrics.widthPixels - a.dp(110), a.dp(300))
        img.setImageBitmap(Sync.qrBitmap(Sync.pairCode(), size))
        card.addView(img, lp(size, size))
        card.addView(a.note("On the other phone: chef profile → Invite a second chef → Scan their code", 12f).also { it.gravity = Gravity.CENTER }, lp(MATCH, WRAP).m(t = a.dp(10)))
        col.addView(card, lp(MATCH, WRAP))
        // the same thing to type, for a camera that can't scan
        val mc = a.card(C.BUTTER, 14, 12); mc.gravity = Gravity.CENTER_HORIZONTAL
        mc.addView(a.label("Or type this code"))
        val code = a.body(Sync.manualCode(ip, Sync.port, Sync.pendingKey), 28f, C.INK, 800)
        code.letterSpacing = 0.06f; code.gravity = Gravity.CENTER; code.setTextIsSelectable(true)
        code.contentDescription = "manual code"
        mc.addView(code, lp(MATCH, WRAP).m(t = a.dp(4)))
        mc.addView(a.note("Other phone: Invite a second chef → Type their code", 11.5f).also { it.gravity = Gravity.CENTER })
        col.addView(mc, lp(MATCH, WRAP).m(t = a.dp(14)))
        col.addView(a.note("Waiting for the other phone… ($ip)", 11.5f).also { it.gravity = Gravity.CENTER }, lp(MATCH, WRAP).m(t = a.dp(12)))
    }

    private fun scan(col: android.widget.LinearLayout) {
        val frame = FrameLayout(a)
        frame.background = InkBox(a, C.NIGHT, C.MINT, 22f, 2.2f, 5f, 4)
        val side = a.resources.displayMetrics.widthPixels - a.dp(56)
        if (busy) frame.addView(a.icon("sync", 64, C.MINT, C.NIGHT_TEXT), flp(a.dp(64), a.dp(64), Gravity.CENTER))
        else {
            val sc = QrScanner(a) { code -> onCode(code) }
            scanner = sc
            frame.addView(sc, flp(MATCH, MATCH).m(a.dp(6), a.dp(6), a.dp(11), a.dp(11)))
        }
        col.addView(frame, lp(MATCH, side))
        val msg = if (busy) "Found it, syncing…" else if (error.isNotEmpty()) error else "Point the camera at the code on the other phone. Tap to focus."
        col.addView(a.note(msg, 13f, if (error.isNotEmpty() && !busy) C.RASP else C.INK2).also { it.gravity = Gravity.CENTER }, lp(MATCH, WRAP).m(t = a.dp(12)))
        val type = a.chip("Type the code instead", false, C.BUTTER, "pencil", 13f)
        type.onClick { scanner?.close(); mode = 4; error = ""; rebuild() }
        col.addView(type, lp(WRAP, WRAP).m(t = a.dp(12)).also { it.gravity = Gravity.CENTER_HORIZONTAL })
    }

    /** Typing the code shown under the other phone's QR (for a camera that won't scan). */
    private fun typeCode(col: android.widget.LinearLayout) {
        col.addView(a.note("On the other phone, open Show my code. Type the code under the QR, like “20 · K7QP · X2MD”. Spaces and dots don't matter.", 13f), lp(MATCH, WRAP).m(b = a.dp(12)))
        val e = Fields.line(a, "20 · K7QP · X2MD", 24f)
        e.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        e.typeface = F.black(a); e.gravity = Gravity.CENTER
        e.contentDescription = "code field"
        col.addView(e, lp(MATCH, WRAP))
        if (busy) col.addView(a.note("Found it, syncing…", 13f).also { it.gravity = Gravity.CENTER }, lp(MATCH, WRAP).m(t = a.dp(12)))
        else if (error.isNotEmpty()) col.addView(a.note(error, 13f, C.RASP).also { it.gravity = Gravity.CENTER }, lp(MATCH, WRAP).m(t = a.dp(12)))
        val go = a.bigButton("Join", C.MINT, C.INK, C.INK, "check")
        go.onClick {
            val ip = Sync.localIp()
            if (ip.isEmpty()) { error = "This phone isn't on a Wi-Fi network."; rebuild(); return@onClick }
            val code = Sync.parseManual(e.text.toString(), ip)
            if (code == null) { error = "That code doesn't look right. It's a number, then 8 letters and digits."; rebuild(); return@onClick }
            a.hideKeyboard()
            join(code)
        }
        col.addView(go, lp(MATCH, WRAP).m(t = a.dp(16)))
        if (!busy) a.showKeyboard(e)
    }

    private fun join(code: Sync.Code) {
        busy = true; error = ""
        rebuild()
        Sync.syncWith(a, code.host, code.port, code.key) { err ->
            busy = false
            if (err == null) mode = 3
            else error = if (err == Sync.TOO_OLD || err == Sync.TOO_MANY || err == Sync.NOT_YOURS) err else if (err == Sync.NOT_PAIRED) "That code didn't match. Check the code on the other phone and try again." else "Couldn't reach the other phone. Are both on the same Wi-Fi with Praliné open on Show my code?"
            rebuild()
        }
    }

    fun onCode(text: String) {
        if (busy) return
        val code = Sync.parseCode(text)
        if (code == null) { error = "That isn't a Praliné code."; rebuild(); return }
        scanner?.close()
        join(code)
    }

    private fun paired(col: android.widget.LinearLayout) {
        val peer = Store.peer
        val card = a.card(C.MINT, 16, 16); card.gravity = Gravity.CENTER_HORIZONTAL
        if (peer != null) card.addView(Medallion(a, peer.portrait, C.MINT), lp(a.dp(90), a.dp(90)))
        card.addView(a.script((peer?.name ?: "A chef") + " joined the party", 32f).also { it.gravity = Gravity.CENTER }, lp(MATCH, WRAP).m(t = a.dp(6)))
        card.addView(a.note("Your cookbooks are merged. They'll keep syncing whenever both phones have Praliné open on this Wi-Fi.", 12.5f).also { it.gravity = Gravity.CENTER }, lp(MATCH, WRAP).m(t = a.dp(4)))
        col.addView(card, lp(MATCH, WRAP).m(t = a.dp(8)))
        val done = a.bigButton("Back to the kitchen", C.RASP, C.WHITE, C.INK, "check")
        done.onClick { a.pop() }
        col.addView(done, lp(MATCH, WRAP).m(t = a.dp(18)))
    }

    /** The phone showing its code learns it was scanned when the other phone syncs in. */
    override fun onSynced() {
        if (mode == 1 && Store.pairKey.isNotEmpty() && Store.peer != null) { mode = 3; rebuild() }
    }

    override fun onHide() { scanner?.close() }
}
