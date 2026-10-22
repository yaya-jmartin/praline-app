package com.yaya.praline

/** Test helpers for 1.4 parties: a paired phone without a real pairing. */
object TestParty {
    fun pair(ch: Chef, key: String = "k"): Peer {
        Store.chefs[ch.id] = ch
        val p = Peer(ch.id); p.key = key; p.secret = "c2VjcmV0"; p.confirmed = true
        Store.peers.add(p)
        return p
    }
    /** A mission as 1.3 left it: both chefs walking from the start at one-chef distances. */
    fun legacy(w: Missions.World) {
        val p = Store.parties[w.id] ?: return
        p.legacyUntil = Long.MAX_VALUE / 4
        for (ch in Store.party()) p.members[ch.id] = Missions.started(w)
    }
}
