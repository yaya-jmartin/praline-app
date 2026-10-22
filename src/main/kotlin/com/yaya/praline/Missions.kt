package com.yaya.praline

import org.json.JSONObject

/**
 * Party missions: up to four chefs travel a world map together. Fame any member earns from cooking moves
 * the party along the trail. Each recipe spot on the way is a must-cook: the party reaches it, its recipe
 * joins the grimoire (and stays), and the trail beyond stays locked until one of the members cooks it.
 *
 * 1.4: one chef starts a mission and invites the others, who accept or decline ([MissionParty]). Each spot sits
 * its gap × the party size further on, so a bigger party walks further; when someone joins, only the spots not
 * reached yet stretch. Progress is worked out from the members' cooking history since they joined, so all phones
 * agree after a sync; only start times and who joined travel.
 */
object Missions {
    /** [gap] is the fame between the spot before (or the start) and this one, for a party of one; [xp] is where it sits for one. */
    class Spot(val key: String, val label: String, val xp: Int, val gap: Int, val recipe: () -> Recipe)
    class World(val id: String, val name: String, val asset: String, val spots: List<Spot>)

    /** Where the party is: [done] spots cooked, standing at the next one or on the way to it. */
    class Progress(
        val world: World,
        /** Party fame since the mission started. */
        val xp: Int,
        /** Spots whose recipe has been cooked (the trail is open past them). */
        val done: Int,
        /** The party stands at spot [done] and must cook it before going on. */
        val waiting: Boolean,
        /** 0..1 along the trail between the start (0) and the last spot. */
        val along: Float,
        /** Fame kept for later while the party waits at a spot. */
        val banked: Int,
        /** Spots the party has reached (their recipes are in the grimoire). */
        val reached: Int,
        /** Party fame at which each spot is reached, for this party's size (later spots at today's size). */
        val at: IntArray = IntArray(0),
        /** How many chefs walk now. */
        val size: Int = 1
    ) {
        val complete get() = done >= world.spots.size
        val next: Spot? get() = if (complete) null else world.spots[done]
        /** Party fame at which spot [i] is (or will be) reached. */
        fun xpAt(i: Int): Int = if (i < at.size) at[i] else world.spots[Math.min(i, world.spots.size - 1)].xp
        /** Fame still needed to reach the next spot (0 while waiting there). */
        val toNext: Int get() { if (next == null) return 0; return if (waiting) 0 else Math.max(0, xpAt(done) - xp) }
        /** Fame from the previous spot to the next one, for the bar. */
        val span: Int get() { if (next == null) return 1; val prev = if (done == 0) 0 else xpAt(done - 1); return Math.max(1, xpAt(done) - prev) }
        val into: Int get() { if (next == null) return span; return if (waiting) span else span - Math.max(0, xpAt(done) - xp) }
    }

    /** Fame between spots for one chef (cumulative = where each spot sits). A few cooks between early spots, more later. */
    val GAPS = intArrayOf(80, 120, 140, 160, 180, 200, 220, 240, 260, 300)

    fun recipeId(world: String, i: Int) = "mission-$world-${i + 1}"

    val SUNFORGE: World by lazy {
        val spots = ArrayList<Spot>()
        var at = 0
        for (i in Templates.SUNFORGE.indices) {
            val gap = GAPS[Math.min(i, GAPS.size - 1)]
            at += gap
            val t = Templates.SUNFORGE[i]
            val idx = i
            spots.add(Spot(t.key, if (i == Templates.SUNFORGE.size - 1) "The Calice" else t.title, at, gap) { Templates.build("sunforge", idx, t) })
        }
        World("sunforge", "The Sunforge Sands", "missions/calice", spots)
    }
    val all: List<World> get() = listOf(SUNFORGE)
    fun world(id: String): World? { for (w in all) if (w.id == id) return w; return null }

    /** When this mission started (the first chef who set off); 0 = not yet. */
    fun started(w: World): Long = Store.missions[w.id] ?: 0L

    fun party(w: World): MissionParty? = Store.parties[w.id]

    /** True when this chef walks this mission (started it or accepted the invite). */
    fun joined(w: World): Boolean = party(w)?.members?.containsKey(Store.me.id) == true

    /** An invite waiting for this chef's answer. */
    fun invitedHere(w: World): Boolean {
        val p = party(w) ?: return false
        return started(w) > 0L && !p.members.containsKey(Store.me.id) && p.invited.containsKey(Store.me.id) && !p.declined.contains(Store.me.id)
    }

    /** The chefs on this mission, this chef first if they joined. */
    fun members(w: World): List<Chef> {
        val p = party(w) ?: return emptyList()
        val out = ArrayList<Chef>()
        for ((id, _) in p.members.entries.sortedBy { it.value }) { val ch = Store.chef(id) ?: continue; if (ch === Store.me) out.add(0, ch) else out.add(ch) }
        return out
    }

    /** Starts the mission with this chef in the party and invites [invite] (chef ids). */
    fun start(c: android.content.Context?, w: World, at: Long = System.currentTimeMillis(), invite: Collection<String> = emptyList()) {
        if (started(w) > 0L) return
        Store.missions[w.id] = at
        val p = Store.parties[w.id] ?: MissionParty().also { Store.parties[w.id] = it }
        p.by = Store.me.id
        p.members[Store.me.id] = at
        for (id in invite) if (id != Store.me.id && p.invited.size + 1 < Store.PARTY_MAX) p.invited[id] = at
        if (c != null) Store.save(c)
    }

    /** Invites more chefs to a mission under way (the party stays at four at most). */
    fun invite(c: android.content.Context?, w: World, ids: Collection<String>, at: Long = System.currentTimeMillis()) {
        val p = party(w) ?: return
        for (id in ids) {
            if (p.members.containsKey(id) || p.members.size + p.invited.keys.count { it !in p.members && it !in p.declined } >= Store.PARTY_MAX) continue
            p.invited[id] = at; p.declined.remove(id)
        }
        if (c != null) Store.save(c)
    }

    /** Accepts the invite: this chef's cooks count from now, and the spots not reached yet stretch. */
    fun accept(c: android.content.Context?, w: World, at: Long = System.currentTimeMillis()): Boolean {
        val p = party(w) ?: return false
        if (p.members.size >= Store.PARTY_MAX) return false
        p.members[Store.me.id] = at
        p.declined.remove(Store.me.id)
        if (c != null) Store.save(c)
        return true
    }

    fun decline(c: android.content.Context?, w: World) {
        val p = party(w) ?: return
        p.declined.add(Store.me.id)
        if (c != null) Store.save(c)
    }

    /** The members' cooks since each joined (before [MissionParty.legacyUntil]: since the start, as in 1.3). */
    private fun cooks(w: World, p: MissionParty): List<CookEntry> {
        val since = started(w)
        val out = ArrayList<CookEntry>()
        for ((id, joined) in p.members) {
            val ch = Store.chef(id) ?: continue
            val from = if (joined <= p.legacyUntil) since else Math.max(since, joined)
            for (e in ch.history) if (e.at >= from) out.add(e)
        }
        return out
    }

    /** How many chefs walk at time [t]: before [MissionParty.legacyUntil] the old one-chef distances. */
    private fun sizeAt(p: MissionParty, t: Long): Int {
        if (t < p.legacyUntil) return 1
        var n = 0
        for ((_, j) in p.members) if (j <= t) n++
        return Math.max(1, Math.min(Store.PARTY_MAX, n))
    }

    /** Where the party stands now. */
    fun progress(w: World, now: Long = System.currentTimeMillis()): Progress {
        val since = started(w)
        val n = w.spots.size
        val party = party(w)
        if (since <= 0L || party == null) return Progress(w, 0, 0, false, 0f, 0, 0, base(w), 1)
        val all = cooks(w, party).sortedBy { it.at }
        // moments the party grows: the spot being walked to stretches then (spots already reached never move)
        val growth = ArrayList<Long>()
        for ((_, j) in party.members) if (j > since) growth.add(j)
        if (party.legacyUntil > since) growth.add(party.legacyUntil)
        growth.sort()
        val at = IntArray(n)
        var size = sizeAt(party, since)
        var xp = 0
        var done = 0
        at[0] = w.spots[0].gap * size
        var g = 0
        fun grow(t: Long) {
            size = sizeAt(party, t)
            // the next spot, if not reached yet, sits its gap × the new size past the spot before
            if (done < n && xp < at[done]) at[done] = (if (done == 0) 0 else at[done - 1]) + w.spots[done].gap * size
        }
        // walk the cooks in time order: fame moves the party on; a spot's recipe counts once the party stood there
        for (e in all) {
            while (g < growth.size && growth[g] <= e.at) { grow(growth[g]); g++ }
            if (done < n && e.recipeId == recipeId(w.id, done) && xp >= at[done]) {
                done++
                if (done < n) at[done] = at[done - 1] + w.spots[done].gap * size
            }
            xp += e.fame
        }
        while (g < growth.size && growth[g] <= now) { grow(growth[g]); g++ }
        // spots further on, at today's size
        for (i in done + 1 until n) at[i] = at[i - 1] + w.spots[i].gap * size
        if (done >= n) return Progress(w, xp, done, false, 1f, 0, done, at, size)
        val waiting = xp >= at[done]
        val prevXp = if (done == 0) 0 else at[done - 1]
        val pos = if (waiting) at[done] else xp
        val along = alongFor(done, n, prevXp, at[done], pos)
        val banked = if (waiting) xp - at[done] else 0
        return Progress(w, xp, done, waiting, along, banked, if (waiting) done + 1 else done, at, size)
    }

    private fun base(w: World): IntArray { val a = IntArray(w.spots.size); for (i in a.indices) a[i] = w.spots[i].xp; return a }

    /** 0..1 along the trail: spots sit at even steps of the map's spot positions ([Spot] k at [spotAlong]). */
    var spotAlong: FloatArray = FloatArray(0)

    private fun alongFor(done: Int, n: Int, prevXp: Int, nextXp: Int, pos: Int): Float {
        val a0 = if (done == 0) 0f else at(done - 1, n)
        val a1 = at(done, n)
        val span = Math.max(1, nextXp - prevXp)
        val f = Math.max(0f, Math.min(1f, (pos - prevXp).toFloat() / span))
        return a0 + (a1 - a0) * f
    }

    private fun at(i: Int, n: Int): Float = if (i < spotAlong.size) spotAlong[i] else (i + 1).toFloat() / n

    /** Puts the recipes of every reached spot in the grimoire (once; a chef who deletes one keeps it deleted). */
    fun unlock(c: android.content.Context?, w: World, p: Progress = progress(w)): List<Recipe> {
        val added = ArrayList<Recipe>()
        for (i in 0 until Math.min(p.reached, w.spots.size)) {
            val id = recipeId(w.id, i)
            if (Store.recipe(id) != null || Store.tombstones.containsKey(id)) continue
            val r = w.spots[i].recipe()
            Store.recipes.add(r)
            added.add(r)
        }
        if (added.isNotEmpty() && c != null) Store.save(c)
        return added
    }

    /** Merges another phone's mission start times (the earliest wins) and parties. */
    fun merge(remote: JSONObject?, parties: JSONObject? = null) {
        if (remote != null) for (k in remote.keys()) {
            val t = remote.optLong(k)
            if (t <= 0L) continue
            val mine = Store.missions[k] ?: 0L
            if (mine == 0L || t < mine) Store.missions[k] = t
        }
        if (parties != null) for (k in parties.keys()) {
            val p = Store.parties[k] ?: MissionParty().also { Store.parties[k] = it }
            p.merge(parties.getJSONObject(k))
        }
    }

    fun toJson(): JSONObject { val o = JSONObject(); for ((k, v) in Store.missions) o.put(k, v); return o }
    fun partiesJson(): JSONObject { val o = JSONObject(); for ((k, v) in Store.parties) o.put(k, v.toJson()); return o }

    /** Mission invites waiting for this chef, for the home page. */
    fun invites(): List<World> = all.filter { invitedHere(it) }
}

/** Placeholder recipes for the mission spots (for testing the feature; real ones come later). */
object Templates {
    class T(val key: String, val title: String, val cls: String, val tier: String, val effort: Int, val time: Int, val serv: Int,
            val kind: String, val ing: Array<String>, val steps: Array<String>)

    val SUNFORGE = arrayOf(
        T("flatbread", "Dune Flatbread", "monk", "B", 2, 40, 4, Recipe.MEAL,
            arrayOf("300 g flour", "180 ml warm water", "1 tsp salt", "2 tbsp olive oil", "1 tsp za'atar"),
            arrayOf("Mix the flour, salt, water and olive oil into a soft dough.", "Knead, then let the dough rest under a cloth.@1200", "Split into 6 balls and roll each one out thin.", "Cook each flatbread in a dry hot pan until it puffs.@90", "Brush with oil and scatter the za'atar.")),
        T("tea", "Mint Desert Tea", "monk", "C", 1, 10, 4, Recipe.BEVERAGE,
            arrayOf("1 tbsp green tea", "1 bunch fresh mint", "3 tbsp sugar", "1 l water"),
            arrayOf("Bring the water to the boil.", "Rinse the tea with a splash of hot water, then pour it away.", "Add the mint, the sugar and the hot water; steep.@240", "Pour from high above the glasses, so the tea foams.")),
        T("tagine", "Oasis Date Tagine", "cleric", "A", 3, 90, 4, Recipe.MEAL,
            arrayOf("800 g lamb shoulder", "2 onions", "12 dates", "1 tsp cinnamon", "1 tsp ginger", "1 pinch saffron", "2 tbsp honey", "50 g almonds", "400 ml stock"),
            arrayOf("Brown the lamb in olive oil.", "Add the onions and the spices; soften.@300", "Pour in the stock, cover and simmer.@3600", "Add the dates and the honey; simmer uncovered.@900", "Scatter toasted almonds before serving.")),
        T("cheese", "Camel-Farm Cheese", "wizard", "B", 3, 60, 4, Recipe.MEAL,
            arrayOf("2 l whole milk", "60 ml lemon juice", "1 tsp salt", "1 tbsp nigella seeds"),
            arrayOf("Warm the milk until it steams, stirring.@600", "Stir in the lemon juice; let it curdle.@300", "Strain the curds through a cloth.", "Salt, press and chill.@1800", "Roll in nigella seeds and slice.")),
        T("couscous", "Saffron Couscous", "paladin", "B", 2, 25, 4, Recipe.MEAL,
            arrayOf("300 g couscous", "1 pinch saffron", "400 ml stock", "30 g butter", "1 handful raisins", "1 handful parsley"),
            arrayOf("Steep the saffron in the hot stock.@300", "Pour over the couscous with the raisins; cover.@300", "Fluff with a fork and the butter.", "Finish with chopped parsley.")),
        T("rice", "Caravan Spice Rice", "sorcerer", "A", 2, 35, 4, Recipe.MEAL,
            arrayOf("300 g basmati rice", "1 onion", "1 tsp cumin", "4 cardamom pods", "1 cinnamon stick", "2 bay leaves", "600 ml stock", "50 g pistachios"),
            arrayOf("Rinse the rice until the water runs clear.", "Fry the onion and the whole spices in butter.@300", "Add the rice and the stock; cover and simmer.@900", "Rest off the heat, lid on.@600", "Scatter the pistachios.")),
        T("honey", "Pyramid Honey Cakes", "bard", "A", 3, 45, 8, Recipe.DESSERT,
            arrayOf("200 g flour", "100 g butter", "120 g honey", "2 eggs", "1 tsp baking powder", "1 tsp orange blossom water", "30 g sesame seeds"),
            arrayOf("Beat the butter and the honey until pale.", "Beat in the eggs and the orange blossom water.", "Fold in the flour and the baking powder.", "Spoon into a mini tin, scatter sesame, bake.@1200", "Glaze with warm honey.")),
        T("shakshuka", "Sand-Skillet Shakshuka", "sorcerer", "S", 2, 30, 2, Recipe.MEAL,
            arrayOf("1 onion", "1 red pepper", "2 cloves garlic", "400 g crushed tomatoes", "1 tsp paprika", "1 tsp cumin", "4 eggs", "1 handful coriander"),
            arrayOf("Soften the onion and the pepper in a skillet.@480", "Add the garlic and the spices.@60", "Pour in the tomatoes and simmer.@600", "Make 4 wells and crack in the eggs; cover.@360", "Scatter coriander and serve in the skillet.")),
        T("falafel", "Glassblower's Falafel", "rogue", "A", 3, 40, 4, Recipe.MEAL,
            arrayOf("250 g dried chickpeas", "1 onion", "2 cloves garlic", "1 bunch parsley", "1 tsp cumin", "1 tsp coriander", "1 tsp salt", "oil"),
            arrayOf("Soak the chickpeas overnight, then drain.", "Blitz everything into a coarse paste.", "Shape small balls and chill.@900", "Fry in hot oil until deep golden.@240", "Serve with tahini and flatbread.")),
        T("kebab", "Sunforged Kebabs", "ranger", "S", 3, 50, 4, Recipe.MEAL,
            arrayOf("600 g lamb", "1 onion", "1 tsp cumin", "1 tsp sumac", "1 tsp paprika", "2 tbsp yogurt", "1 lemon"),
            arrayOf("Grate the onion and mix with the yogurt and the spices.", "Marinate the lamb.@1800", "Thread onto skewers.", "Grill over the hottest heat, turning.@600", "Squeeze lemon over and scatter sumac.")))

    /** One template as a recipe with a fixed id, so both phones add the same one. */
    fun build(world: String, i: Int, t: T): Recipe {
        val r = Recipe(Missions.recipeId(world, i))
        r.title = t.title; r.cls = t.cls; r.tier = t.tier; r.effort = t.effort; r.timeMin = t.time; r.servings = t.serv; r.kind = t.kind
        r.world = world
        r.created = 0L; r.updated = 0L
        val v = Variant("Classic")
        for (line in t.ing) v.ingredients.add(Ingredient.parse(line))
        for (s in t.steps) {
            val at = s.lastIndexOf('@')
            if (at > 0) v.steps.add(Step(s.substring(0, at), s.substring(at + 1).toInt()))
            else v.steps.add(Step(s))
        }
        r.variants.add(v)
        return r
    }
}
