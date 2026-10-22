package com.yaya.praline

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Editing a recipe the party already has. Each change is either kept on this phone (a My Variant
 * special), offered as a new variant for everyone, or offered as the new main recipe. Every other chef
 * accepts or refuses it on their own phone, and specials can be shared on to the other chefs too.
 * Offers and answers travel from phone to phone ([Store.relay], [Store.votes]) until everyone answered.
 */
object Share {
    /** Offers and answers older than this are dropped, answered or not. */
    const val KEEP_MS = 30L * 24 * 3600_000L

    /** Other phones have this recipe, so changes to it go through the other chefs. */
    fun isShared(r: Recipe): Boolean = Store.paired() && Store.party().isNotEmpty() && Store.peerSeen.contains(r.id)

    /** "Sam", "Sam and Alex", "Sam, Alex and Jo": the other chefs of the party. */
    fun peerName(): String {
        val names = Store.party().map { it.name }
        return when (names.size) {
            0 -> "the other chef"
            1 -> names[0]
            else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
        }
    }

    private fun send(c: Context?, p: Proposal): Proposal {
        Store.outbox.add(p)
        if (c != null) Store.save(c)
        return p
    }

    private fun proposal(r: Recipe, kind: String, data: JSONObject, title: String, at: Long) =
        Proposal(Proposal.newId(), r.id, kind, data, Store.me.id, Store.me.name, at, title)

    /** Offers the whole recipe as it is now to replace the shared one. Any earlier main offer for it is replaced. */
    fun proposeMain(c: Context?, r: Recipe, at: Long = System.currentTimeMillis()): Proposal {
        Store.outbox.removeAll { it.recipeId == r.id && it.kind == "main" }
        return send(c, proposal(r, "main", r.toJson(), r.title, at))
    }

    /** Offers [v] as a new variant of [r] for both chefs. */
    fun proposeVariant(c: Context?, r: Recipe, v: Variant, at: Long = System.currentTimeMillis()): Proposal =
        send(c, proposal(r, "variant", v.toJson(), r.title, at))

    /** Gives one of this phone's specials to the other chef, to keep for themself. */
    fun shareSpecial(c: Context?, r: Recipe, v: Variant, at: Long = System.currentTimeMillis()): Proposal =
        send(c, proposal(r, "special", v.toJson(), r.title, at))

    // ---- this phone's specials ------------------------------------------------------
    /** Variant tabs count up from 0; this phone's specials come after them as [SPECIAL] + k. */
    const val SPECIAL = 100
    fun variantAt(r: Recipe, idx: Int): Variant {
        if (idx >= SPECIAL) { val l = Store.mine[r.id]; val k = idx - SPECIAL; if (l != null && k < l.size) return l[k] }
        return r.variant(idx)
    }

    fun specials(recipeId: String): MutableList<Variant> {
        val hit = Store.mine[recipeId]
        if (hit != null) return hit
        val l = ArrayList<Variant>(); Store.mine[recipeId] = l; return l
    }

    fun uniqueName(names: List<String>, want: String): String {
        if (!names.contains(want)) return want
        var n = 2
        while (names.contains("$want $n")) n++
        return "$want $n"
    }

    private fun namesOf(l: List<Variant>): List<String> { val out = ArrayList<String>(); for (v in l) out.add(v.name); return out }

    /** Saves [v] as a My Variant special of [r] on this phone. Returns its index. */
    fun addSpecial(c: Context?, r: Recipe, v: Variant): Int {
        val l = specials(r.id)
        v.name = uniqueName(namesOf(l), v.name.ifEmpty { "My variant" })
        l.add(v)
        Store.me.specials++
        Store.me.updated = System.currentTimeMillis()
        if (c != null) Store.save(c)
        return l.size - 1
    }

    // ---- answering the other chef ------------------------------------------------------
    /** What a proposal would change, in a few words, for its card. */
    fun describe(p: Proposal): String {
        val r = Store.recipe(p.recipeId)
        return when (p.kind) {
            "variant" -> "a new variant, “" + (p.variant()?.name ?: "?") + "”"
            "special" -> "their special “" + (p.variant()?.name ?: "?") + "”, just for you"
            else -> {
                val n = p.recipe() ?: return "changes"
                if (r == null) "the recipe" else diff(r, n)
            }
        }
    }

    /** "title, steps of Classic, tier" — what differs between two versions of a recipe. */
    fun diff(old: Recipe, new: Recipe): String {
        val out = ArrayList<String>()
        if (old.title != new.title) out.add("the name")
        if (old.photo != new.photo) out.add("the photo")
        if (old.tier != new.tier) out.add("the tier")
        if (old.cls != new.cls || old.dessert != new.dessert) out.add("the class")
        if (old.effort != new.effort || old.timeMin != new.timeMin || old.servings != new.servings) out.add("time, effort or servings")
        if (old.fame != new.fame) out.add("its fame")
        if (old.equipment != new.equipment || old.equipCount != new.equipCount) out.add("the equipment")
        for (v in new.variants) {
            var ov: Variant? = null
            for (x in old.variants) if (x.name == v.name) ov = x
            if (ov == null) { out.add("a new variant “${v.name}”"); continue }
            val a = JSONArray(); for (i in ov.ingredients) a.put(i.toJson())
            val b = JSONArray(); for (i in v.ingredients) b.put(i.toJson())
            if (a.toString() != b.toString()) out.add("ingredients of ${v.name}")
            val sa = JSONArray(); for (s in ov.steps) sa.put(s.toJson())
            val sb = JSONArray(); for (s in v.steps) sb.put(s.toJson())
            if (sa.toString() != sb.toString()) out.add("steps of ${v.name}")
        }
        for (x in old.variants) { var found = false; for (v in new.variants) if (v.name == x.name) found = true; if (!found) out.add("removes “${x.name}”") }
        if (out.isEmpty()) return "small changes"
        return out.joinToString(", ")
    }

    private fun answer(c: Context?, p: Proposal, verdict: String) {
        Store.inbox.removeAll { it.id == p.id }
        Store.votes[p.id + "|" + Store.me.id] = verdict
        Store.handled.add(p.id)
        if (c != null) { Store.save(c); Store.cleanPhotos(c) }
    }

    fun accept(c: Context?, p: Proposal) {
        val r = Store.recipe(p.recipeId)
        when (p.kind) {
            "main" -> {
                val n = p.recipe()
                if (n != null) {
                    if (r != null) n.lastCooked = Math.max(n.lastCooked, r.lastCooked)
                    n.updated = System.currentTimeMillis()
                    val i = Store.recipes.indexOfFirst { it.id == n.id }
                    if (i >= 0) Store.recipes[i] = n else if (Store.tombstones[n.id] == null) Store.recipes.add(n)
                }
            }
            "variant" -> {
                val v = p.variant()
                if (v != null && r != null) {
                    v.name = uniqueName(namesOf(r.variants), v.name)
                    r.variants.add(v)
                    r.updated = System.currentTimeMillis()
                }
            }
            "special" -> {
                val v = p.variant()
                if (v != null && r != null) {
                    val l = specials(r.id)
                    v.name = uniqueName(namesOf(l), p.fromName + "'s " + v.name)
                    l.add(v)
                }
            }
        }
        answer(c, p, "accepted")
    }

    fun refuse(c: Context?, p: Proposal) = answer(c, p, "refused")

    /** True once every other chef of the party (but the one who sent it) answered [p]. */
    private fun allAnswered(p: Proposal): Boolean {
        for (ch in Store.party()) if (ch.id != p.from && !Store.votes.containsKey(p.id + "|" + ch.id)) return false
        if (p.from != Store.me.id && !Store.votes.containsKey(p.id + "|" + Store.me.id)) return false
        return true
    }

    /** Merging another phone's offers and answers (called from the sync). Returns lines to tell the chef. */
    fun merge(remote: JSONObject, now: Long = System.currentTimeMillis()): List<String> {
        val news = ArrayList<String>()
        // answers travel with everyone's offers; nothing is taken back
        val vo = remote.optJSONObject("votes") ?: JSONObject()
        for (k in vo.keys()) if (!Store.votes.containsKey(k)) Store.votes[k] = vo.getString(k)
        val ps = remote.optJSONArray("proposals") ?: JSONArray()
        for (i in 0 until ps.length()) {
            val p = Proposal.fromJson(ps.getJSONObject(i))
            if (p.from == Store.me.id || now - p.at > KEEP_MS) continue
            if (Store.relay.none { it.id == p.id } && !allAnswered(p)) Store.relay.add(p)
            if (Store.handled.contains(p.id) || Store.inbox.any { it.id == p.id } || Store.votes.containsKey(p.id + "|" + Store.me.id)) continue
            Store.inbox.add(p)
            news.add("${p.fromName} sent a change to ${p.title}")
        }
        // the other chefs' answers to what this phone sent, each said once
        for (p in Store.outbox) for (ch in Store.party()) {
            val k = p.id + "|" + ch.id
            val v = Store.votes[k] ?: continue
            if (!Store.told.add(k)) continue
            news.add(if (v == "accepted") "${ch.name} accepted your change to ${p.title}" else "${ch.name} kept ${p.title} as it was")
        }
        // offers everyone answered (or a month old) are done
        Store.outbox.removeAll { now - it.at > KEEP_MS || (Store.party().isNotEmpty() && allAnswered(it)) }
        Store.relay.removeAll { now - it.at > KEEP_MS || allAnswered(it) }
        val live = HashSet<String>()
        for (p in Store.outbox) live.add(p.id); for (p in Store.relay) live.add(p.id); for (p in Store.inbox) live.add(p.id)
        Store.votes.keys.retainAll { live.contains(it.substringBefore('|')) }
        Store.told.retainAll { live.contains(it.substringBefore('|')) }
        return news
    }
}
