package com.yaya.praline

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The private line between two phones of the party (sync protocol 3, and 4 from 1.4).
 *
 * Each sync starts with a fresh key exchange (elliptic-curve Diffie-Hellman), so someone listening on the Wi-Fi
 * learns nothing, and recordings can't be opened later. Both phones then prove they know the shared secret
 * without sending it: the long pairing secret once paired, or the pair code while pairing (and once, when two
 * 1.2 phones move to protocol 3). Everything after that travels encrypted and sealed (AES-256-GCM), so a
 * changed or replayed piece is refused.
 */
object SecureLink {
    private val rnd = SecureRandom()

    fun random(n: Int): ByteArray { val b = ByteArray(n); rnd.nextBytes(b); return b }

    fun newKeyPair(): KeyPair {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"), rnd)
        return g.generateKeyPair()
    }

    fun publicBytes(kp: KeyPair): ByteArray = kp.public.encoded

    /** The shared value both phones compute from their own key pair and the other's public key. */
    fun agree(mine: KeyPair, theirs: ByteArray): ByteArray {
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(theirs))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(mine.private)
        ka.doPhase(pub, true)
        return ka.generateSecret()
    }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        for (p in parts) { m.update(p.size.toByte()); m.update((p.size shr 8).toByte()); m.update(p) }
        return m.doFinal()
    }

    fun sha256(vararg parts: ByteArray): ByteArray { val d = MessageDigest.getInstance("SHA-256"); for (p in parts) d.update(p); return d.digest() }

    /**
     * What a pair code proves with: never the code itself. Since 1.4 it is stretched (PBKDF2, [CODE_ROUNDS] rounds),
     * so someone who records a pairing can't try codes quickly afterwards.
     */
    fun codeSecret(code: String): ByteArray = synchronized(codeCache) {
        codeCache[code]?.let { return it }
        val spec = javax.crypto.spec.PBEKeySpec(code.toCharArray(), "praline-pair-code-4".toByteArray(), CODE_ROUNDS, 256)
        val out = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        if (codeCache.size > 8) codeCache.clear()
        codeCache[code] = out
        out
    }
    const val CODE_ROUNDS = 60_000
    private val codeCache = HashMap<String, ByteArray>()

    fun same(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    /** Everything one sync derives from the key exchange and the secret both phones prove they know. */
    class Keys(auth: ByteArray, shared: ByteArray, transcript: ByteArray) {
        private val k = hmac(auth, "praline-4".toByteArray(), shared, transcript)
        val clientProof: ByteArray = hmac(k, "client proof".toByteArray())
        val serverProof: ByteArray = hmac(k, "server proof".toByteArray())
        val toServer: ByteArray = hmac(k, "client to server".toByteArray())
        val toClient: ByteArray = hmac(k, "server to client".toByteArray())
        /** The long pairing secret, saved by both phones the first time they pair (or move to protocol 3). */
        val pairing: ByteArray = hmac(k, "pairing secret".toByteArray())
    }

    fun transcript(clientPub: ByteArray, serverPub: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray): ByteArray =
        sha256(clientPub, serverPub, clientNonce, serverNonce)

    /** Wraps a socket's streams so everything written is encrypted and everything read is checked and decrypted. */
    fun wrap(rawIn: InputStream, rawOut: OutputStream, readKey: ByteArray, writeKey: ByteArray): Pair<DataInputStream, DataOutputStream> =
        Pair(DataInputStream(SealedIn(rawIn, readKey)), DataOutputStream(SealedOut(rawOut, writeKey)))

    const val CHUNK = 64 * 1024

    private fun nonce(counter: Long): ByteArray {
        val n = ByteArray(12)
        for (i in 0 until 8) n[4 + i] = (counter shr (56 - 8 * i)).toByte()
        return n
    }

    /** Collects bytes and sends them as sealed chunks: length, then the encrypted chunk with its tag. */
    class SealedOut(private val out: OutputStream, key: ByteArray) : OutputStream() {
        private val k = SecretKeySpec(key, "AES")
        private val buf = ByteArray(CHUNK)
        private var n = 0
        private var counter = 0L

        override fun write(b: Int) { if (n == CHUNK) seal(); buf[n++] = b.toByte() }
        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off; var left = len
            while (left > 0) {
                if (n == CHUNK) seal()
                val k2 = Math.min(left, CHUNK - n)
                System.arraycopy(b, o, buf, n, k2); n += k2; o += k2; left -= k2
            }
        }
        override fun flush() { if (n > 0) seal(); out.flush() }
        override fun close() { flush(); out.close() }

        private fun seal() {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, nonce(counter++)))
            val sealed = c.doFinal(buf, 0, n)
            val len = sealed.size
            out.write(len ushr 24); out.write(len ushr 16); out.write(len ushr 8); out.write(len)
            out.write(sealed)
            n = 0
        }
    }

    /** Reads sealed chunks; a chunk that was changed, reordered or replayed fails its check and ends the sync. */
    class SealedIn(input: InputStream, key: ByteArray) : InputStream() {
        private val inp = DataInputStream(input)
        private val k = SecretKeySpec(key, "AES")
        private var buf = ByteArray(0)
        private var pos = 0
        private var counter = 0L

        private fun fill(): Boolean {
            while (pos >= buf.size) {
                val len = try { inp.readInt() } catch (e: EOFException) { return false }
                if (len < 16 || len > CHUNK + 16) throw IllegalStateException("bad sealed chunk")
                val sealed = ByteArray(len); inp.readFully(sealed)
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, nonce(counter++)))
                buf = c.doFinal(sealed); pos = 0
            }
            return true
        }
        override fun read(): Int { if (!fill()) return -1; return buf[pos++].toInt() and 0xFF }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val k2 = Math.min(len, buf.size - pos)
            System.arraycopy(buf, pos, b, off, k2); pos += k2
            return k2
        }
        override fun available(): Int = buf.size - pos
        override fun close() { inp.close() }
    }
}
