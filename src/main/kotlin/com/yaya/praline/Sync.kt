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
 * Two phones, two chefs, one cookbook. Phones pair once by QR code, then sync over
 * the home Wi-Fi whenever both have Praliné open: recipes, photos, the week plan,
 * and each chef's profile (the other phone's chef shows up read-only).
 */
object Sync {
    const val PORT = 47820
    const val TYPE = "_praline._tcp."
    /** Praliné 1.1 speaks version 2: shared-recipe changes, and photos and clips sent as raw bytes. */
    const val VERSION = 2
    const val NOT_HOME = "Not on your home Wi-Fi, so nothing was sent."
    const val TOO_OLD = "Update Praliné on the other phone to 1.1 so the two can sync again."
    /** Things to tell the chef after a sync ("Yaya accepted your change to Tatin"). */
    val news: MutableList<String> = ArrayList()

    // ---- pure merge logic (tested) ----------------------------------------------
    fun payload(c: Context?, includePhotos: Boolean = true): JSONObject {
        val o = JSONObject()
        o.put("v", VERSION)
        o.put("key", Store.pairKey)
        o.put("me", Store.me.toJson())
        val r = JSONArray(); for (x in Store.recipes) r.put(x.toJson()); o.put("recipes", r)
        val t = JSONObject(); for ((k, v) in Store.tombstones) t.put(k, v); o.put("tomb", t)
        val p = JSONObject(); for ((k, v) in Store.plan) p.put(k, v); o.put("plan", p)
        o.put("planUpdated", Store.planUpdated)
        val ob = JSONArray(); for (x in Store.outbox) ob.put(x.toJson()); o.put("outbox", ob)
        val an = JSONObject(); for ((k, v) in Store.answers) an.put(k, v); o.put("answers", an)
        if (includePhotos && c != null) { val ph = JSONArray(); for (n in localPhotos(c)) ph.put(n); o.put("photos", ph) }
        return o
    }

    /**
     * Merges the other phone's state into ours. New recipes come in; deletions stick. A recipe both phones
     * already share never changes behind a chef's back: edits to it travel as proposals, answered one by one.
     * Recipes not yet known to be shared still follow newest edit wins. Returns how many recipes changed.
     */
    fun merge(remote: JSONObject): Int {
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
        // from now on both phones have these, so edits to them go through the other chef
        for (i in 0 until rs.length()) Store.peerSeen.add(rs.getJSONObject(i).optString("id"))
        val gone = ArrayList<String>(); for (id in Store.peerSeen) if (Store.tombstones.containsKey(id)) gone.add(id)
        for (id in gone) Store.peerSeen.remove(id)
        news.addAll(Share.merge(remote))
        Store.inbox.removeAll { Store.recipe(it.recipeId) == null }
        if (remote.optLong("planUpdated") > Store.planUpdated) {
            Store.plan.clear()
            val p = remote.optJSONObject("plan") ?: JSONObject()
            val pk = p.keys(); while (pk.hasNext()) { val k = pk.next(); Store.plan[k] = p.getString(k) }
            Store.planUpdated = remote.optLong("planUpdated")
        }
        val me = remote.optJSONObject("me")
        if (me != null) { val chef = Chef.fromJson(me); if (chef.id != Store.me.id) Store.peer = chef }
        return changed
    }

    /** Every photo and clip the shared state points at: recipes, and changes waiting either way. */
    private fun referenced(): List<String> {
        val out = ArrayList<String>()
        for (r in Store.recipes) for (n in r.photos()) if (!out.contains(n)) out.add(n)
        for (p in Store.inbox) for (n in p.photos()) if (!out.contains(n)) out.add(n)
        for (p in Store.outbox) for (n in p.photos()) if (!out.contains(n)) out.add(n)
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
    @Volatile var pendingKey: String = ""   // key shown in the QR while waiting to pair
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
                // only announced on the home Wi-Fi
                if (HomeWifi.here(app)) register(app)
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

    private fun serve(c: Context, s: Socket) {
        try {
            s.soTimeout = 20_000
            val inp = DataInputStream(s.getInputStream().buffered()); val out = DataOutputStream(s.getOutputStream().buffered())
            val hello = recv(inp)
            if (!HomeWifi.here(c)) { send(out, JSONObject().put("error", NOT_HOME)); return }
            val key = hello.optString("key")
            synchronized(lock) {
                val ok = key.isNotEmpty() && (key == Store.pairKey || key == pendingKey)
                if (!ok) { send(out, JSONObject().put("error", "not paired")); return }
                if (hello.optInt("v", 1) < VERSION) { send(out, JSONObject().put("error", TOO_OLD).put("v", VERSION)); return }
                if (key == pendingKey) { Store.pairKey = key; pendingKey = "" }
                Store.peerHost = s.inetAddress.hostAddress ?: ""
                merge(hello)
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
    /** Syncs with the other phone at [host]:[port]. Calls [done] on the main thread with null or an error. */
    fun syncWith(c: Context, host: String, port: Int, key: String, done: (String?) -> Unit) {
        val app = c.applicationContext
        if (!HomeWifi.here(app)) { main.post(object : Runnable { override fun run() { done(NOT_HOME) } }); return }
        Thread(object : Runnable {
            override fun run() {
                val err = try { client(app, host, port, key); null } catch (e: Exception) { e.message ?: "couldn't reach the other phone" }
                main.post(object : Runnable { override fun run() { if (err == null) onSynced?.invoke(); done(err) } })
            }
        }).start()
    }

    fun client(c: Context, host: String, port: Int, key: String) {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), 4000)
            s.soTimeout = 20_000
            val inp = DataInputStream(s.getInputStream().buffered()); val out = DataOutputStream(s.getOutputStream().buffered())
            val hello: JSONObject
            synchronized(lock) { hello = payload(c); hello.put("key", key) }
            send(out, hello)
            val state = recv(inp)
            if (state.has("error")) throw IllegalStateException(state.getString("error"))
            if (state.optInt("v", 1) < VERSION) throw IllegalStateException(TOO_OLD)
            val second = JSONObject()
            synchronized(lock) {
                Store.pairKey = key; Store.peerHost = host; Store.peerPort = port
                merge(state)
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

    private fun register(c: Context) {
        try {
            val nsd = c.getSystemService(NsdManager::class.java) ?: return
            val info = NsdServiceInfo()
            info.serviceName = "praline-" + Store.me.id.substring(0, 8)
            info.serviceType = TYPE
            info.port = port
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) {}
                override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
            }
            regListener = l
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) { }
    }

    private fun unregister(c: Context) {
        try { val l = regListener; if (l != null) c.getSystemService(NsdManager::class.java)?.unregisterService(l) } catch (e: Exception) { }
        regListener = null
        stopDiscovery(c)
    }

    /** Looks for the paired phone on the Wi-Fi and syncs when it shows up. Falls back to its last known address. */
    fun autoSync(c: Context, done: (String?) -> Unit) {
        if (Store.pairKey.isEmpty()) return
        val peer = Store.peer ?: return
        if (!HomeWifi.here(c)) return
        val app = c.applicationContext
        var fired = false
        fun go(host: String, port: Int) { if (fired) return; fired = true; stopDiscovery(app); syncWith(app, host, port, Store.pairKey, done) }
        try {
            val wifi = app.getSystemService(WifiManager::class.java)
            if (wifi != null) { val m = wifi.createMulticastLock("praline"); m.setReferenceCounted(false); m.acquire(); multicast = m }
            val nsd = app.getSystemService(NsdManager::class.java) ?: throw IllegalStateException()
            val want = "praline-" + peer.id.substring(0, 8)
            val l = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(t: String) {}
                override fun onDiscoveryStopped(t: String) {}
                override fun onStartDiscoveryFailed(t: String, e: Int) {}
                override fun onStopDiscoveryFailed(t: String, e: Int) {}
                override fun onServiceLost(i: NsdServiceInfo) {}
                override fun onServiceFound(i: NsdServiceInfo) {
                    if (!i.serviceName.startsWith(want)) return
                    @Suppress("DEPRECATION")
                    nsd.resolveService(i, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(x: NsdServiceInfo, e: Int) {}
                        override fun onServiceResolved(x: NsdServiceInfo) {
                            @Suppress("DEPRECATION") val h = x.host?.hostAddress ?: return
                            main.post(object : Runnable { override fun run() { go(h, x.port) } })
                        }
                    })
                }
            }
            discListener = l
            nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) { }
        // last known address, in case discovery is blocked on this network
        main.postDelayed(object : Runnable {
            override fun run() { if (!fired && Store.peerHost.isNotEmpty()) go(Store.peerHost, if (Store.peerPort > 0) Store.peerPort else PORT) else if (!fired) stopDiscovery(app) }
        }, 6000)
    }

    fun stopDiscovery(c: Context) {
        try { val l = discListener; if (l != null) c.getSystemService(NsdManager::class.java)?.stopServiceDiscovery(l) } catch (e: Exception) { }
        discListener = null
        try { multicast?.release() } catch (e: Exception) { }
        multicast = null
    }

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

    /** What the QR code says: where to find this phone and the secret that proves it was scanned. */
    fun pairCode(): String {
        if (pendingKey.isEmpty()) pendingKey = newKey()
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

    /** Syncs right away when paired (after sending or answering a change). */
    fun nudge(c: Context) {
        if (Store.pairKey.isEmpty() || Store.peer == null) return
        val done: (String?) -> Unit = { }
        if (Store.peerHost.isNotEmpty()) syncWith(c, Store.peerHost, if (Store.peerPort > 0) Store.peerPort else PORT, Store.pairKey) { err -> if (err != null) autoSync(c, done) }
        else autoSync(c, done)
    }

    // ---- manual code, for a phone whose camera can't scan ------------------------------
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    fun newKey(): String {
        val rnd = java.security.SecureRandom()
        val sb = StringBuilder()
        for (i in 0 until 8) sb.append(ALPHABET[rnd.nextInt(ALPHABET.length)])
        return sb.toString()
    }

    /** "20 · K7QP · X2MD": the last number of this phone's address (and port, if not the usual one), then the key. */
    fun manualCode(ip: String, port: Int, key: String): String {
        val last = ip.substringAfterLast('.')
        val where = if (port != PORT) "$last:$port" else last
        return if (key.length == 8) "$where · ${key.substring(0, 4)} · ${key.substring(4)}" else "$where · $key"
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
        if (key.length != 8) return null
        val dot = myIp.lastIndexOf('.')
        if (dot < 0) return null
        return Code(myIp.substring(0, dot + 1) + last, port, key.toString())
    }

    fun unpair(c: Context) { Store.pairKey = ""; Store.peer = null; Store.peerHost = ""; Store.peerPort = 0; pendingKey = ""; Store.save(c) }

    fun qrBitmap(text: String, size: Int): Bitmap {
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size)
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m.get(x, y)) C.INK else C.CARD
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

}
