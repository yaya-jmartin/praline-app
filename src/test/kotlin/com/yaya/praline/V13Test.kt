package com.yaya.praline

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

/** Praliné 1.3: encrypted sync, the pair code never sent, a limit on wrong codes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V13Test {
    private val c get() = RuntimeEnvironment.getApplication()

    @Before fun clean() { Store.reset(); Store.load(c); HomeWifi.assumeHome = true; Sync.clearFailures(); Sync.pendingKey = "" }
    @After fun done() { HomeWifi.assumeHome = false; Sync.stopServer(c); Sync.clearFailures() }

    private fun server() {
        Sync.startServer(c)
        var tries = 0
        while (Sync.port == 0 && tries < 50) { Thread.sleep(20); tries++ }
        Thread.sleep(200)
    }

    private fun bytes(n: Int): ByteArray { val b = ByteArray(n); for (i in 0 until n) b[i] = (i * 31 + 7).toByte(); return b }

    @Test fun sealedStreamsRoundTripAndRefuseTampering() {
        val key = SecureLink.random(32)
        val wire = ByteArrayOutputStream()
        val (_, out) = SecureLink.wrap(ByteArrayInputStream(ByteArray(0)), wire, key, key)
        val big = bytes(200_000)
        out.writeUTF("Tarte Tatin"); out.writeInt(big.size); out.write(big); out.flush()
        val sent = wire.toByteArray()
        assertTrue(sent.size > big.size)
        // nothing readable on the wire
        assertEquals(-1, String(sent, Charsets.ISO_8859_1).indexOf("Tarte Tatin"))
        val (inp, _) = SecureLink.wrap(ByteArrayInputStream(sent), ByteArrayOutputStream(), key, key)
        assertEquals("Tarte Tatin", inp.readUTF())
        val back = ByteArray(inp.readInt()); inp.readFully(back)
        assertArrayEquals(big, back)
        // one changed byte and the chunk is refused
        val bad = sent.clone(); bad[40] = (bad[40].toInt() xor 1).toByte()
        val (inp2, _) = SecureLink.wrap(ByteArrayInputStream(bad), ByteArrayOutputStream(), key, key)
        var refused = false
        try { inp2.readUTF() } catch (e: Exception) { refused = true }
        assertTrue(refused)
        // the wrong key can't open it
        val (inp3, _) = SecureLink.wrap(ByteArrayInputStream(sent), ByteArrayOutputStream(), SecureLink.random(32), key)
        refused = false
        try { inp3.readUTF() } catch (e: Exception) { refused = true }
        assertTrue(refused)
    }

    @Test fun bothPhonesDeriveTheSameKeysOnlyWithTheSameCode() {
        val a = SecureLink.newKeyPair(); val b = SecureLink.newKeyPair()
        val za = SecureLink.agree(a, SecureLink.publicBytes(b)); val zb = SecureLink.agree(b, SecureLink.publicBytes(a))
        assertArrayEquals(za, zb)
        val tr = SecureLink.transcript(SecureLink.publicBytes(a), SecureLink.publicBytes(b), SecureLink.random(32), SecureLink.random(32))
        val ka = SecureLink.Keys(SecureLink.codeSecret("K7QPX2MD"), za, tr)
        val kb = SecureLink.Keys(SecureLink.codeSecret("K7QPX2MD"), zb, tr)
        assertArrayEquals(ka.clientProof, kb.clientProof); assertArrayEquals(ka.toServer, kb.toServer); assertArrayEquals(ka.pairing, kb.pairing)
        val wrong = SecureLink.Keys(SecureLink.codeSecret("K7QPX2ME"), zb, tr)
        assertFalse(SecureLink.same(ka.clientProof, wrong.clientProof))
        assertFalse(SecureLink.same(ka.toServer, ka.toClient))
    }

    /** Sits between the two phones and keeps a copy of every byte, like someone listening on the Wi-Fi. */
    private class Listener(val target: Int) {
        val heard = ByteArrayOutputStream()
        val ss = ServerSocket(0)
        val port get() = ss.localPort
        init {
            val t = Thread(object : Runnable {
                override fun run() {
                    try {
                        while (true) {
                            val a = ss.accept(); val b = Socket("127.0.0.1", target)
                            pump(a.getInputStream(), b.getOutputStream()); pump(b.getInputStream(), a.getOutputStream())
                        }
                    } catch (e: Exception) { }
                }
            })
            t.isDaemon = true; t.start()
        }
        private fun pump(i: InputStream, o: OutputStream) {
            val t = Thread(object : Runnable {
                override fun run() {
                    val buf = ByteArray(8192)
                    try {
                        while (true) {
                            val n = i.read(buf); if (n < 0) break
                            synchronized(heard) { heard.write(buf, 0, n) }
                            o.write(buf, 0, n); o.flush()
                        }
                    } catch (e: Exception) { }
                    try { o.close() } catch (e: Exception) { }
                }
            })
            t.isDaemon = true; t.start()
        }
        fun text(): String = synchronized(heard) { String(heard.toByteArray(), Charsets.ISO_8859_1) }
    }

    @Test fun pairingThenSyncNeverSendsTheCodeOrTheCookbookInTheClear() {
        server()
        val ear = Listener(Sync.port)
        Sync.pendingKey = "K7QPX2MD"
        Store.me.name = "Yaya"
        val r = LogicTest.sample("Secret Soufflé", true, "bard", "S", 3, 40)
        Store.upsert(c, r)
        Sync.client(c, "127.0.0.1", ear.port, "K7QPX2MD")
        assertEquals("K7QPX2MD", Store.pairKey)
        assertEquals("", Sync.pendingKey)
        assertTrue(Store.pairSecret.isNotEmpty())
        assertFalse(Store.secretConfirmed)
        // the next sync proves the long secret, and from then on the short code is no longer accepted
        Sync.client(c, "127.0.0.1", ear.port, "K7QPX2MD")
        assertTrue(Store.secretConfirmed)
        Thread.sleep(200)
        val heard = ear.text()
        assertTrue(heard.length > 1000)
        assertEquals(-1, heard.indexOf("K7QPX2MD"))
        assertEquals(-1, heard.indexOf("Secret Souffl"))
        assertEquals(-1, heard.indexOf("Yaya"))
        assertFalse(Sync.payload(null, false).has("key"))
        // a phone that only knows the short code is refused now
        val secret = Store.pairSecret
        Store.pairSecret = ""
        var err = ""
        try { Sync.client(c, "127.0.0.1", Sync.port, "K7QPX2MD") } catch (e: Exception) { err = e.message ?: "" }
        assertEquals(Sync.NOT_PAIRED, err)
        Store.pairSecret = secret
        // the secret survives a restart
        Store.save(c); Store.reset(); Store.load(c)
        assertEquals(secret, Store.pairSecret); assertTrue(Store.secretConfirmed)
        Sync.unpair(c)
        assertEquals("", Store.pairSecret)
    }

    @Test fun phonesPairedOn12MoveOverWithTheirCode() {
        server()
        // two 1.2 phones: a pair code, no long secret yet
        Store.pairKey = "OLDKEY12"
        Sync.client(c, "127.0.0.1", Sync.port, "OLDKEY12")
        assertTrue(Store.pairSecret.isNotEmpty())
        Sync.client(c, "127.0.0.1", Sync.port, "OLDKEY12")
        assertTrue(Store.secretConfirmed)
    }

    @Test fun fiveWrongCodesLockPairingForTenMinutes() {
        server()
        Sync.pendingKey = "K7QPX2MD"
        for (i in 0 until 5) {
            var err = ""
            try { Sync.client(c, "127.0.0.1", Sync.port, "WRONG00$i") } catch (e: Exception) { err = e.message ?: "" }
            assertEquals(Sync.NOT_PAIRED, err)
        }
        var err = ""
        // even the right code waits now
        try { Sync.client(c, "127.0.0.1", Sync.port, "K7QPX2MD") } catch (e: Exception) { err = e.message ?: "" }
        assertEquals(Sync.TOO_MANY, err)
        assertTrue(Sync.lockedOut())
        assertFalse(Sync.lockedOut(System.currentTimeMillis() + 11 * 60_000L))
        Sync.clearFailures()
        Sync.client(c, "127.0.0.1", Sync.port, "K7QPX2MD")
        assertEquals("K7QPX2MD", Store.pairKey)
    }

    @Test fun anOldPhoneIsToldToUpdate() {
        server()
        val s = Socket("127.0.0.1", Sync.port)
        val out = java.io.DataOutputStream(s.getOutputStream()); val inp = java.io.DataInputStream(s.getInputStream())
        val hello = JSONObject().put("v", 2).put("key", "K7QPX2MD").toString().toByteArray()
        out.writeInt(hello.size); out.write(hello); out.flush()
        val n = inp.readInt(); val buf = ByteArray(n); inp.readFully(buf)
        assertEquals(Sync.TOO_OLD, JSONObject(String(buf)).getString("error"))
        s.close()
    }
}
