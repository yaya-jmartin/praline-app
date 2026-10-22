package com.yaya.praline

import android.content.Context
import android.graphics.Bitmap
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

/**
 * Up to four phones, one chef each, one cookbook. A phone pairs once (QR code or typed code) with any phone of
 * the party, then they sync whenever both have Praliné open: recipes, photos, the week plan, missions, changes
 * offered and answered, and every chef's profile (other chefs show up read-only). What one phone learns it passes
 * on, so phones that never paired with each other still meet through the others.
 *
 * 1.4 (protocol 4): several paired phones, a live sync every few seconds while Praliné is open, the real reason
 * when a sync can't happen, an optional home-Wi-Fi lock, and longer pairing codes that are slow to guess.
 */
object Sync {
    const val PORT = 47820
    const val TYPE = "_praline._tcp."
    /** Praliné 1.4 speaks version 4: several phones, everything encrypted, the pair code never sent (see [SecureLink]). */
    const val VERSION = 4
    const val NOT_HOME = "Not on your home Wi-Fi, so nothing was sent."
    const val THEIRS_NOT_HOME = "The other phone isn't on your home Wi-Fi, or can't tell (is location on there?)."
    const val TOO_OLD = "Update Praliné on the other phone to 1.4 so the two can sync again."
    const val NOT_PAIRED = "not paired"
    const val TOO_MANY = "Too many wrong codes. Wait 10 minutes, then pair again."
    const val NOT_YOURS = "The other phone couldn't prove it's yours, so nothing was sent."
    const val PARTY_FULL = "This party already has four chefs."
    const val NO_ANSWER = "no answer"
    /** How often an open Praliné syncs with each paired phone that is open too. */
    const val LIVE_MS = 15_000L
    /** Things to tell the chef after a sync ("Sam accepted your change to Tatin"). */
    val news: MutableList<String> = ArrayList()

    // ---- pure merge logic (tested) ----------------------------------------------
    fun payload(c: Context?, includePhotos: Boolean = true): JSONObject {
        val o = JSONObject()
        o.put("v", VERSION)
        o.put("me", Store.me.toJson())
        val party = JSONArray(); for (ch in Store.chefs.values) party.put(ch.toJson()); o.put("party", party)
        val r = JSONArray(); for (x in Store.recipes) r.put(x.toJson()); o.put("recipes", r)
        val t = JSONObject(); for ((k, v) in Store.tombstones) t.put(k, v); o.put("tomb", t)
        val p = JSONObject(); for ((k, v) in Store.plan) p.put(k, v); o.put("plan", p)
        o.put("planUpdated", Store.planUpdated)
        o.put("missions", Missions.toJson())
        o.put("parties", Missions.partiesJson())
        val ob = JSONArray(); for (x in Store.outbox) ob.put(x.toJson()); for (x in Store.relay) ob.put(x.toJson()); o.put("proposals", ob)
        val vo = JSONObject(); for ((k, v) in Store.votes) vo.put(k, v); o.put("votes", vo)
        if (includePhotos && c != null) { val ph = JSONArray(); for (n in localPhotos(c)) ph.put(n); o.put("photos", ph) }
        return o
    }

    /** Keeps the newer copy of a chef this phone hears about (their profile and cooking history). */
    private fun learnChef(o: JSONObject) {
        val ch = Chef.fromJson(o)
        if (ch.id.isEmpty() || ch.id == Store.me.id) return
        val known = Store.chefs[ch.id]
        if (known == null || ch.updated > known.updated || (ch.updated == known.updated && ch.history.size > known.history.size)) Store.chefs[ch.id] = ch
    }

    /**
     * Merges another phone's state into ours. New recipes come in; deletions stick. A recipe the party already
     * shares never changes behind a chef's back: edits to it travel as proposals, answered one by one.
     * Recipes not yet known to be shared still follow newest edit wins. Returns how many recipes changed.
     */
    fun merge(remote: JSONObject, from: Peer? = null): Int {
        var changed = 0
        val t = remote.optJSONObject("tomb") ?: JSONObject()
        val keys = t.keys()
        while (keys.hasNext()) { val k = keys.next(); val at = t.getLong(k); if (at > (Store.tombstones[k] ?: 0L)) Store.tombstones[k] = at }
        val it = Store.recipes.iterator()
        while (it.hasNext()) { val r = it.next(); val dead = Store.tombstones[r.id]; if (dead != null && dead >= r.updated) { it.remove(); changed++ } }
        val rs = remote.optJSONArray("recipes") ?: JSONArray()
        for (i in 0 until rs.length()) {
            val r = Recipe.fromJson(rs.getJSONObject(i))
            val dead = Store.tombstones[r.id]
            if (dead != null && dead >= r.updated) continue
            val idx = Store.recipes.indexOfFirst { it.id == r.id }
            if (idx < 0) { Store.recipes.add(r); changed++ }
            else {
                val mine = Store.recipes[idx]
                if (!Store.peerSeen.contains(r.id) && r.updated > mine.updated) { r.lastCooked = Math.max(r.lastCooked, mine.lastCooked); Store.recipes[idx] = r; changed++ }
                else if (r.lastCooked > mine.lastCooked) mine.lastCooked = r.lastCooked
            }
        }
        // from now on other phones have these, so edits to them go through the other chefs
        for (i in 0 until rs.length()) Store.peerSeen.add(rs.getJSONObject(i).optString("id"))
        val gone = ArrayList<String>(); for (id in Store.peerSeen) if (Store.tombstones.containsKey(id)) gone.add(id)
        for (id in gone) Store.peerSeen.remove(id)
        // chefs: the one on the other phone, and everyone it knows
        val me = remote.optJSONObject("me")
        if (me != null) {
            learnChef(me)
            val id = me.optString("id")
            if (from != null && id.isNotEmpty() && id != Store.me.id) {
                from.chefId = id
                // the same chef paired again: keep only the newest pairing
                Store.peers.removeAll { it !== from && it.chefId == id }
            }
        }
        val party = remote.optJSONArray("party") ?: JSONArray()
        for (i in 0 until party.length()) learnChef(party.getJSONObject(i))
        news.addAll(Share.merge(remote))
        Store.inbox.removeAll { Store.recipe(it.recipeId) == null }
        Missions.merge(remote.optJSONObject("missions"), remote.optJSONObject("parties"))
        if (remote.optLong("planUpdated") > Store.planUpdated) {
            Store.plan.clear()
            val p = remote.optJSONObject("plan") ?: JSONObject()
            val pk = p.keys(); while (pk.hasNext()) { val k = pk.next(); Store.plan[k] = p.getString(k) }
            Store.planUpdated = remote.optLong("planUpdated")
        }
        return changed
    }

    /** Every photo and clip the shared state points at: recipes, and changes waiting either way. */
    private fun referenced(): List<String> {
        val out = ArrayList<String>()
        for (r in Store.recipes) for (n in r.photos()) if (!out.contains(n)) out.add(n)
        for (p in Store.inbox) for (n in p.photos()) if (!out.contains(n)) out.add(n)
        for (p in Store.outbox) for (n in p.photos()) if (!out.contains(n)) out.add(n)
        for (p in Store.relay) for (n in p.photos()) if (!out.contains(n)) out.add(n)
        return out
    }

    fun localPhotos(c: Context): List<String> {
        val out = ArrayList<String>()
        for (n in referenced()) if (Store.photoFile(c, n).exists()) out.add(n)
        return out
    }

    fun neededPhotos(c: Context): List<String> {
        val out = ArrayList<String>()
        for (n in referenced()) if (!Store.photoFile(c, n).exists()) out.add(n)
        return out
    }

    private fun safe(n: String) = n.isNotEmpty() && !n.contains("/") && !n.contains("..") && !n.contains("\\")

    /** Sends the named files as raw bytes: count, then name, size and bytes for each. */
    fun sendFiles(c: Context, out: DataOutputStream, names: JSONArray?) {
        val list = ArrayList<java.io.File>()
        if (names != null) for (i in 0 until names.length()) {
            val n = names.optString(i)
            if (!safe(n)) continue
            val f = Store.photoFile(c, n)
            if (f.exists()) list.add(f)
        }
        out.writeInt(list.size)
        val buf = ByteArray(64 * 1024)
        for (f in list) {
            out.writeUTF(f.name)
            out.writeLong(f.length())
            f.inputStream().use { inp ->
                var left = f.length()
                while (left > 0) {
                    val n = inp.read(buf, 0, Math.min(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n); left -= n
                }
                while (left > 0) { val n = Math.min(buf.size.toLong(), left).toInt(); java.util.Arrays.fill(buf, 0.toByte()); out.write(buf, 0, n); left -= n }
            }
        }
        out.flush()
    }

    /** Receives files sent by [sendFiles] into the photos folder (through a temporary file, so a cut transfer leaves nothing half-written). */
    fun recvFiles(c: Context, inp: DataInputStream) {
        val count = inp.readInt()
        if (count < 0 || count > 10_000) throw IllegalStateException("bad file count")
        val buf = ByteArray(64 * 1024)
        for (i in 0 until count) {
            val name = inp.readUTF()
            val size = inp.readLong()
            if (size < 0 || size > 200L * 1024 * 1024) throw IllegalStateException("bad file size")
            val ok = safe(name)
            val tmp = java.io.File(Store.photosDir(c), ".in_" + (if (ok) name else "x"))
            java.io.FileOutputStream(tmp).use { out ->
                var left = size
                while (left > 0) {
                    val n = inp.read(buf, 0, Math.min(buf.size.toLong(), left).toInt())
                    if (n < 0) throw java.io.EOFException()
                    out.write(buf, 0, n); left -= n
                }
            }
            if (ok) { val dst = Store.photoFile(c, name); if (!tmp.renameTo(dst)) { dst.delete(); tmp.renameTo(dst) } } else tmp.delete()
        }
    }

    private fun send(out: DataOutputStream, o: JSONObject) { val b = o.toString().toByteArray(Charsets.UTF_8); out.writeInt(b.size); out.write(b); out.flush() }
    private fun recv(inp: DataInputStream): JSONObject {
        val n = inp.readInt()
        if (n < 0 || n > 200_000_000) throw IllegalStateException("bad frame")
        val b = ByteArray(n); inp.readFully(b)
        return JSONObject(String(b, Charsets.UTF_8))
    }

    // ---- server ---------------------------------------------------------------
    @Volatile private var server: ServerSocket? = null
    /** Codes shown while waiting to pair: the long one in the QR, the short one to type. */
    @Volatile var pendingKey: String = ""
    @Volatile var pendingShort: String = ""
    var onSynced: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private val lock = Object()
    var port = PORT

    fun startServer(c: Context) {
        if (server != null) return
        val app = c.applicationContext
        val t = Thread(object : Runnable {
            override fun run() {
                val ss = try { ServerSocket(PORT) } catch (e: Exception) { try { ServerSocket(0) } catch (x: Exception) { return } }
                server = ss; port = ss.localPort
                // only announced where syncing is allowed; [announce] tries again later (location switched on, home reached)
                announce(app)
                while (!ss.isClosed) {
                    try {
                        val s = ss.accept()
                        Thread(object : Runnable { override fun run() { serve(app, s) } }).start()
                    } catch (e: Exception) { if (ss.isClosed) break }
                }
            }
        })
        t.isDaemon = true
        t.start()
    }

    fun stopServer(c: Context) {
        try { server?.close() } catch (e: Exception) { }
        server = null
        unregister(c)
    }

    // wrong codes or secrets: after 5 in 10 minutes, nobody pairs or syncs for 10 minutes
    private val failures = ArrayList<Long>()
    private const val FAIL_MAX = 5
    private const val FAIL_WINDOW = 10 * 60_000L

    fun lockedOut(now: Long = System.currentTimeMillis()): Boolean = synchronized(failures) {
        failures.removeAll { now - it > FAIL_WINDOW }
        failures.size >= FAIL_MAX
    }
    private fun failed() { synchronized(failures) { failures.add(System.currentTimeMillis()) } }
    fun clearFailures() { synchronized(failures) { failures.clear() } }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    /** A secret a phone asking to sync may prove: a paired phone's secret, or a code shown while pairing. */
    private class Candidate(val auth: ByteArray, val code: String, val peer: Peer?)

    private fun candidates(mode: String): List<Candidate> {
        val out = ArrayList<Candidate>()
        if (mode == "secret") { for (p in Store.peers) if (p.secret.isNotEmpty()) out.add(Candidate(unb64(p.secret), "", p)) }
        else {
            if (pendingKey.isNotEmpty()) out.add(Candidate(SecureLink.codeSecret(pendingKey), pendingKey, null))
            if (pendingShort.isNotEmpty()) out.add(Candidate(SecureLink.codeSecret(pendingShort), pendingShort, null))
        }
        return out
    }

    private fun serve(c: Context, s: Socket) {
        try {
            s.soTimeout = 20_000
            val bin = s.getInputStream().buffered(); val bout = s.getOutputStream().buffered()
            val pin = DataInputStream(bin); val pout = DataOutputStream(bout)
            val hello = recv(pin)
            if (!HomeWifi.here(c)) { send(pout, JSONObject().put("error", THEIRS_NOT_HOME)); return }
            if (hello.optInt("v", 1) < VERSION) { send(pout, JSONObject().put("error", TOO_OLD).put("v", VERSION)); return }
            if (lockedOut()) { send(pout, JSONObject().put("error", TOO_MANY)); return }
            val mode = hello.optString("mode")
            if (mode == "code" && Store.peers.size >= Store.PARTY_MAX - 1) { send(pout, JSONObject().put("error", PARTY_FULL)); return }
            val theirPub = unb64(hello.optString("epk")); val theirNonce = unb64(hello.optString("nonce"))
            val kp = SecureLink.newKeyPair(); val nonce = SecureLink.random(32)
            send(pout, JSONObject().put("v", VERSION).put("epk", b64(SecureLink.publicBytes(kp))).put("nonce", b64(nonce)))
            val shared = SecureLink.agree(kp, theirPub)
            val tr = SecureLink.transcript(theirPub, SecureLink.publicBytes(kp), theirNonce, nonce)
            val proof = unb64(recv(pin).optString("proof"))
            var hit: Candidate? = null; var keys: SecureLink.Keys? = null
            synchronized(lock) {
                for (cand in candidates(mode)) {
                    val k = SecureLink.Keys(cand.auth, shared, tr)
                    if (SecureLink.same(k.clientProof, proof)) { keys = k; hit = cand; break }
                }
            }
            val k = keys; val h = hit
            if (k == null || h == null) { failed(); send(pout, JSONObject().put("error", NOT_PAIRED)); return }
            send(pout, JSONObject().put("proof", b64(k.serverProof)))
            val peer: Peer
            synchronized(lock) {
                if (h.peer != null) { peer = h.peer; peer.confirmed = true }
                else {
                    // a new phone joins: both pending codes are used up
                    peer = Peer(); peer.key = h.code; peer.secret = b64(k.pairing); peer.confirmed = false
                    Store.peers.add(peer)
                    pendingKey = ""; pendingShort = ""
                }
                Store.save(c)
            }
            val (inp, out) = SecureLink.wrap(bin, bout, k.toServer, k.toClient)
            val state = recv(inp)
            synchronized(lock) {
                peer.host = s.inetAddress.hostAddress ?: ""
                peer.lastSync = System.currentTimeMillis()
                merge(state, peer)
                Store.save(c)
                val reply = payload(c)
                val want = JSONArray(); for (n in neededPhotos(c)) want.put(n)
                reply.put("want", want)
                send(out, reply)
            }
            val second = recv(inp)
            recvFiles(c, inp)
            sendFiles(c, out, second.optJSONArray("want"))
            synchronized(lock) { Store.save(c) }
            main.post(object : Runnable { override fun run() { onSynced?.invoke() } })
        } catch (e: Exception) {
        } finally { try { s.close() } catch (e: Exception) { } }
    }

    // ---- client ---------------------------------------------------------------
    /** Pairs with the phone showing [key] at [host]:[port], or syncs with the paired phone that has that key. [done] gets null or the reason, on the main thread. */
    fun syncWith(c: Context, host: String, port: Int, key: String, done: (String?) -> Unit) {
        val app = c.applicationContext
        val why = HomeWifi.why(app)
        if (why != null) { main.post(object : Runnable { override fun run() { done(why) } }); return }
        Thread(object : Runnable {
            override fun run() {
                val err = try { client(app, host, port, key); null } catch (e: Exception) { reason(e) }
                main.post(object : Runnable { override fun run() { if (err == null) onSynced?.invoke(); done(err) } })
            }
        }).start()
    }

    /** Syncs with one paired phone at its last address. */
    fun syncPeer(c: Context, p: Peer, done: (String?) -> Unit) {
        val app = c.applicationContext
        val why = HomeWifi.why(app)
        if (why != null) { main.post(object : Runnable { override fun run() { done(why) } }); return }
        if (p.host.isEmpty()) { main.post(object : Runnable { override fun run() { done(NO_ANSWER) } }); return }
        synchronized(busy) { if (busy.contains(p)) { main.post(object : Runnable { override fun run() { done(null) } }); return }; busy.add(p) }
        Thread(object : Runnable {
            override fun run() {
                val err = try { clientPeer(app, p, p.host, if (p.port > 0) p.port else PORT); null } catch (e: Exception) { reason(e) }
                synchronized(busy) { busy.remove(p) }
                main.post(object : Runnable { override fun run() { if (err == null) onSynced?.invoke(); done(err) } })
            }
        }).start()
    }
    private val busy = HashSet<Peer>()

    /** What went wrong, in words: the other phone's own answer, or that it didn't answer. */
    fun reason(e: Exception): String {
        val m = e.message ?: ""
        if (e is IllegalStateException && m.isNotEmpty() && m != "bad frame") return m
        return NO_ANSWER
    }

    /** One sentence for the chef about a failed sync with [name]'s phone. */
    fun explain(err: String, name: String): String = when (err) {
        NO_ANSWER -> "$name's phone didn't answer. Is Praliné open on it, on the same Wi-Fi?"
        NOT_PAIRED -> "$name's phone doesn't know this one any more. Pair again."
        else -> err
    }

    /** Pairing with a code, or a sync with the paired phone that has [key] (tests and phones that paired by this code). */
    fun client(c: Context, host: String, port: Int, key: String) {
        val known = synchronized(lock) { Store.peers.firstOrNull { it.key == key && it.secret.isNotEmpty() } }
        if (known != null) clientPeer(c, known, host, port) else clientCode(c, host, port, key)
    }

    fun clientCode(c: Context, host: String, port: Int, key: String) = talk(c, host, port, null, key)
    fun clientPeer(c: Context, p: Peer, host: String, port: Int) = talk(c, host, port, p, "")

    private fun talk(c: Context, host: String, port: Int, known: Peer?, key: String) {
        if (known == null && Store.peers.size >= Store.PARTY_MAX - 1) throw IllegalStateException(PARTY_FULL)
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), 4000)
            s.soTimeout = 20_000
            val bin = s.getInputStream().buffered(); val bout = s.getOutputStream().buffered()
            val pin = DataInputStream(bin); val pout = DataOutputStream(bout)
            // the long secret once paired; the code only while pairing
            val auth: ByteArray; val mode: String
            synchronized(lock) {
                if (known != null) { auth = unb64(known.secret); mode = "secret" }
                else { auth = SecureLink.codeSecret(key); mode = "code" }
            }
            val kp = SecureLink.newKeyPair(); val nonce = SecureLink.random(32)
            send(pout, JSONObject().put("v", VERSION).put("mode", mode).put("epk", b64(SecureLink.publicBytes(kp))).put("nonce", b64(nonce)))
            val hi = recv(pin)
            if (hi.has("error")) throw IllegalStateException(hi.getString("error"))
            if (hi.optInt("v", 1) < VERSION) throw IllegalStateException(TOO_OLD)
            val theirPub = unb64(hi.optString("epk"))
            val tr = SecureLink.transcript(SecureLink.publicBytes(kp), theirPub, nonce, unb64(hi.optString("nonce")))
            val k = SecureLink.Keys(auth, SecureLink.agree(kp, theirPub), tr)
            send(pout, JSONObject().put("proof", b64(k.clientProof)))
            val ok = recv(pin)
            if (ok.has("error")) throw IllegalStateException(ok.getString("error"))
            if (!SecureLink.same(unb64(ok.optString("proof")), k.serverProof)) throw IllegalStateException(NOT_YOURS)
            val (inp, out) = SecureLink.wrap(bin, bout, k.toClient, k.toServer)
            val hello: JSONObject
            val peer: Peer
            synchronized(lock) {
                if (known != null) { peer = known; peer.confirmed = true }
                else { peer = Peer(); peer.key = key; peer.secret = b64(k.pairing); peer.confirmed = false; Store.peers.add(peer) }
                hello = payload(c)
            }
            send(out, hello)
            val state = recv(inp)
            if (state.has("error")) throw IllegalStateException(state.getString("error"))
            val second = JSONObject()
            synchronized(lock) {
                peer.host = host; peer.port = port; peer.lastSync = System.currentTimeMillis()
                merge(state, peer)
                Store.save(c)
                val want = JSONArray(); for (n in neededPhotos(c)) want.put(n)
                second.put("want", want)
            }
            send(out, second)
            sendFiles(c, out, state.optJSONArray("want"))
            recvFiles(c, inp)
            synchronized(lock) { Store.save(c) }
        } finally { try { s.close() } catch (e: Exception) { } }
    }

    // ---- discovery on the home Wi-Fi ----------------------------------------------
    private var regListener: NsdManager.RegistrationListener? = null
    private var discListener: NsdManager.DiscoveryListener? = null
    private var multicast: WifiManager.MulticastLock? = null

    /** Tells the network this phone is here, once syncing is allowed (late is fine: location switched on, home reached). */
    fun announce(c: Context) {
        if (regListener != null || server == null) return
        if (HomeWifi.here(c)) register(c.applicationContext)
    }

    private fun register(c: Context) {
        try {
            val nsd = c.getSystemService(NsdManager::class.java) ?: return
            val info = NsdServiceInfo()
            info.serviceName = "praline-" + Store.me.id.substring(0, 8)
            info.serviceType = TYPE
            info.port = port
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) {}
                override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) { if (regListener === this) regListener = null }
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
            }
            regListener = l
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) { regListener = null }
    }

    private fun unregister(c: Context) {
        try { val l = regListener; if (l != null) c.getSystemService(NsdManager::class.java)?.unregisterService(l) } catch (e: Exception) { }
        regListener = null
        stopDiscovery(c)
    }

    /**
     * Finds the paired phones on the Wi-Fi and syncs with each one that shows up; a phone that doesn't show up
     * is tried at its last known address. [done] gets one answer per paired phone: its chef's name and null or the reason.
     */
    fun autoSync(c: Context, done: (Peer, String?) -> Unit) {
        val peers = ArrayList(Store.peers.filter { it.secret.isNotEmpty() })
        if (peers.isEmpty()) return
        val why = HomeWifi.why(c)
        if (why != null) { for (p in peers) done(p, why); return }
        val app = c.applicationContext
        val fired = HashSet<Peer>()
        fun go(p: Peer, host: String, port: Int) {
            if (fired.contains(p)) return
            fired.add(p)
            if (fired.size == peers.size) stopDiscovery(app)
            p.host = host; p.port = port
            syncPeer(app, p) { err -> done(p, err) }
        }
        try {
            val wifi = app.getSystemService(WifiManager::class.java)
            if (wifi != null) { val m = wifi.createMulticastLock("praline"); m.setReferenceCounted(false); m.acquire(); multicast = m }
            val nsd = app.getSystemService(NsdManager::class.java) ?: throw IllegalStateException()
            stopDiscovery(app)
            val l = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(t: String) {}
                override fun onDiscoveryStopped(t: String) {}
                override fun onStartDiscoveryFailed(t: String, e: Int) {}
                override fun onStopDiscoveryFailed(t: String, e: Int) {}
                override fun onServiceLost(i: NsdServiceInfo) {}
                override fun onServiceFound(i: NsdServiceInfo) {
                    val p = peers.firstOrNull { it.chefId.length >= 8 && i.serviceName.startsWith("praline-" + it.chefId.substring(0, 8)) } ?: return
                    @Suppress("DEPRECATION")
                    nsd.resolveService(i, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(x: NsdServiceInfo, e: Int) {}
                        override fun onServiceResolved(x: NsdServiceInfo) {
                            @Suppress("DEPRECATION") val h = x.host?.hostAddress ?: return
                            main.post(object : Runnable { override fun run() { go(p, h, x.port) } })
                        }
                    })
                }
            }
            discListener = l
            nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) { }
        // last known addresses, in case discovery is blocked on this network
        main.postDelayed(object : Runnable {
            override fun run() {
                for (p in peers) if (!fired.contains(p)) { if (p.host.isNotEmpty()) go(p, p.host, if (p.port > 0) p.port else PORT) else { fired.add(p); done(p, NO_ANSWER) } }
                stopDiscovery(app)
            }
        }, 6000)
    }

    fun stopDiscovery(c: Context) {
        try { val l = discListener; if (l != null) c.getSystemService(NsdManager::class.java)?.stopServiceDiscovery(l) } catch (e: Exception) { }
        discListener = null
        try { multicast?.release() } catch (e: Exception) { }
        multicast = null
    }

    /**
     * The live sync (1.4): while Praliné is open, each paired phone that answered lately is synced every [LIVE_MS];
     * one that didn't is looked for again now and then. Quiet: nothing is said unless something came in.
     */
    fun live(c: Context, now: Long = System.currentTimeMillis()) {
        announce(c)
        if (!Store.paired() || HomeWifi.why(c) != null) return
        var lost = false
        for (p in Store.peers) {
            if (p.secret.isEmpty() || now - p.lastSync < LIVE_MS) continue
            if (p.host.isNotEmpty() && now - p.lastSync < 10 * 60_000L) syncPeer(c, p) { }
            else lost = true
        }
        if (lost && now - lastLook > 60_000L) { lastLook = now; autoSync(c) { _, _ -> } }
    }
    private var lastLook = 0L

    // ---- pairing ---------------------------------------------------------------
    /** Set by tests, which have no Wi-Fi. */
    var fixedIp = ""

    fun localIp(): String {
        if (fixedIp.isNotEmpty()) return fixedIp
        try {
            val ifs = NetworkInterface.getNetworkInterfaces()
            while (ifs.hasMoreElements()) {
                val nif = ifs.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && a.isSiteLocalAddress) return a.hostAddress ?: ""
                }
            }
        } catch (e: Exception) { }
        return ""
    }

    /** What the QR code says: where to find this phone and the long secret that proves it was scanned. */
    fun pairCode(): String {
        if (pendingKey.isEmpty()) pendingKey = newKey(QR_LEN)
        if (pendingShort.isEmpty()) pendingShort = newKey(CODE_LEN)
        return "praline:pair?h=" + localIp() + "&p=" + port + "&k=" + pendingKey
    }

    class Code(val host: String, val port: Int, val key: String)

    fun parseCode(s: String): Code? {
        if (!s.startsWith("praline:pair?")) return null
        var h = ""; var p = PORT; var k = ""
        for (part in s.substring(13).split("&")) {
            val kv = part.split("=", limit = 2)
            if (kv.size != 2) continue
            when (kv[0]) { "h" -> h = kv[1]; "p" -> p = kv[1].toIntOrNull() ?: PORT; "k" -> k = kv[1] }
        }
        return if (h.isEmpty() || k.isEmpty()) null else Code(h, p, k)
    }

    /** Syncs right away with every paired phone (after sending or answering a change). */
    fun nudge(c: Context) {
        for (p in Store.peers) {
            if (p.secret.isEmpty()) continue
            if (p.host.isNotEmpty()) syncPeer(c, p) { err -> if (err != null && err == NO_ANSWER) autoSync(c) { _, _ -> } }
            else { autoSync(c) { _, _ -> }; return }
        }
    }

    // ---- codes: a long one in the QR, a shorter one to type --------------------------
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    /** About 128 bits: the QR code can carry a long secret nobody types. */
    const val QR_LEN = 26
    /** About 60 bits, and slow to try (see [SecureLink.codeSecret]): typed as three groups of four. */
    const val CODE_LEN = 12

    fun newKey(len: Int = CODE_LEN): String {
        val rnd = java.security.SecureRandom()
        val sb = StringBuilder()
        for (i in 0 until len) sb.append(ALPHABET[rnd.nextInt(ALPHABET.length)])
        return sb.toString()
    }

    /** "20 · K7QP · X2MD · 9FHT": the last number of this phone's address (and port, if not the usual one), then the code. */
    fun manualCode(ip: String, port: Int, key: String): String {
        val last = ip.substringAfterLast('.')
        val where = if (port != PORT) "$last:$port" else last
        return if (key.length == CODE_LEN) "$where · ${key.substring(0, 4)} · ${key.substring(4, 8)} · ${key.substring(8)}" else "$where · $key"
    }

    /** Reads a typed manual code, filling in the network from this phone's own address. */
    fun parseManual(typed: String, myIp: String): Code? {
        val t = typed.trim().uppercase()
        var i = 0
        val num = StringBuilder()
        while (i < t.length && Character.isDigit(t[i])) { num.append(t[i]); i++ }
        var port = PORT
        if (i < t.length && t[i] == ':') {
            i++; val p = StringBuilder()
            while (i < t.length && Character.isDigit(t[i])) { p.append(t[i]); i++ }
            port = p.toString().toIntOrNull() ?: PORT
        }
        val last = num.toString().toIntOrNull() ?: return null
        if (last < 0 || last > 255) return null
        val key = StringBuilder()
        while (i < t.length) { val ch = t[i]; if (ALPHABET.indexOf(ch) >= 0) key.append(ch); i++ }
        if (key.length != CODE_LEN) return null
        val dot = myIp.lastIndexOf('.')
        if (dot < 0) return null
        return Code(myIp.substring(0, dot + 1) + last, port, key.toString())
    }

    /** Forgets one paired phone. Its chef stays in the party list while another phone still brings news of them. */
    fun unpair(c: Context, p: Peer) {
        Store.peers.remove(p)
        if (Store.peers.none { it.chefId == p.chefId }) Store.chefs.remove(p.chefId)
        pendingKey = ""; pendingShort = ""
        Store.save(c)
    }

    fun qrBitmap(text: String, size: Int): Bitmap {
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size)
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m.get(x, y)) C.INK else C.CARD
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

}
