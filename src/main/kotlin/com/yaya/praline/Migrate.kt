package com.yaya.praline

import org.json.JSONObject

/**
 * Bringing a saved cookbook from an older Praliné up to date, once, when it is read. Every step reads the old
 * shape and leaves nothing behind; the file is written in the new shape on the next save.
 */
object Migrate {
    /**
     * 1.3 -> 1.4: one paired phone becomes the first entry of [Store.peers]; answers this chef gave become votes;
     * a mission started before 1.4 keeps everything it walked so far ([MissionParty.legacyUntil]) and its party is
     * both chefs, so only the spots not reached yet stretch with the party size.
     */
    fun from13(o: JSONObject, now: Long = System.currentTimeMillis()) {
        val oldPeer = o.optJSONObject("peer")
        if (oldPeer != null && Store.peers.isEmpty()) {
            val ch = Chef.fromJson(oldPeer)
            if (ch.id != Store.me.id) {
                Store.chefs[ch.id] = ch
                val key = o.optString("pairKey"); val secret = o.optString("pairSecret")
                if (key.isNotEmpty() || secret.isNotEmpty()) {
                    val p = Peer(ch.id)
                    p.key = key; p.secret = secret; p.confirmed = o.optBoolean("secretOk")
                    p.host = o.optString("peerHost"); p.port = o.optInt("peerPort")
                    Store.peers.add(p)
                }
            }
        }
        val an = o.optJSONObject("answers")
        if (an != null) for (k in an.keys()) Store.votes[k + "|" + Store.me.id] = an.getString(k)
        for ((world, start) in Store.missions) {
            if (Store.parties.containsKey(world)) continue
            val party = MissionParty()
            party.by = Store.me.id
            party.legacyUntil = now
            party.members[Store.me.id] = start
            for (ch in Store.party()) party.members[ch.id] = start
            Store.parties[world] = party
        }
    }
}
