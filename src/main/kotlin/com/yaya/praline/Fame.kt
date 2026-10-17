package com.yaya.praline

/** Recipe classes, D&D style. */
class RClass(val key: String, val name: String, val icon: String, val color: Int, val blurb: String)

object Classes {
    val all = listOf(
        RClass("barbarian", "Barbarian", "axe", C.CARAMEL, "hearty, big portions"),
        RClass("bard", "Bard", "note", C.LILAC, "a crowd-pleaser"),
        RClass("cleric", "Cleric", "heart", C.RASP, "comfort food that heals"),
        RClass("druid", "Druid", "leaf", C.PIST, "green, garden-grown"),
        RClass("monk", "Monk", "lotus", C.MINT, "simple, few ingredients"),
        RClass("paladin", "Paladin", "shield", C.BLUE, "classic, by the book"),
        RClass("ranger", "Ranger", "bow", C.TANG, "grilled, smoky, outdoorsy"),
        RClass("rogue", "Rogue", "dagger", C.RASP, "quick and sneaky"),
        RClass("sorcerer", "Sorcerer", "flame", C.TANG, "bold, spicy, a little chaotic"),
        RClass("wizard", "Wizard", "hat", C.LILAC, "technical, precise"))
    fun get(key: String): RClass { for (c in all) if (c.key == key) return c; return all[5] }
}

object Tiers {
    val all = listOf("S", "A", "B", "C", "D")
    fun color(t: String): Int = when (t) { "S" -> C.BUTTER; "A" -> C.PIST; "B" -> C.MINT; "C" -> C.BEIGE; else -> C.LILAC }
    fun rank(t: String): Int { val i = all.indexOf(t); return if (i < 0) 9 else i }
}

class Stat(val key: String, val score: Int, val color: Int) {
    val mod: Int get() = Math.floorDiv(score - 10, 2)
    val modText: String get() = if (mod >= 0) "+$mod" else "−${-mod}"
}

class Feat(val icon: String, val color: Int, val title: String, val earned: Boolean)

/** Fame, levels, stats and weapons, all worked out from what the chef actually cooked. */
object Fame {
    fun dc(effort: Int): Int = when (effort) { 1 -> 5; 2 -> 10; 3 -> 12; 4 -> 15; else -> 20 }
    fun effortWord(effort: Int): String = when (effort) { 1 -> "trivial"; 2 -> "easy"; 3 -> "medium"; 4 -> "hard"; else -> "deadly" }

    /** Fame a recipe is worth at full strength: its own custom amount, or worked out from the effort. */
    fun base(r: Recipe): Int = if (r.fame > 0) r.fame else 40 + r.effort * 20

    const val WINDOW = 20          // how many recent cooks count for repeats
    const val REPEAT_CUT = 0.15    // each earlier cook of it in that window takes off 15%
    const val FLOOR = 0.25         // never below a quarter
    const val FRESH_DAYS = 30      // a month without it and it's worth full fame again
    const val FIRST_BONUS = 30

    /** How many of the chef's last [WINDOW] cooks were this recipe, within the last month. */
    fun repeats(r: Recipe, chef: Chef, now: Long): Int {
        val since = now - FRESH_DAYS * 86_400_000L
        var n = 0
        val h = chef.history
        val from = Math.max(0, h.size - WINDOW)
        for (i in from until h.size) { val e = h[i]; if (e.recipeId == r.id && e.at > since && e.at <= now) n++ }
        return n
    }

    fun multiplier(repeats: Int): Double = Math.max(FLOOR, 1.0 - REPEAT_CUT * repeats)

    /** Fame for cooking [r] now: base × repeat multiplier, plus a bonus the very first time. */
    fun reward(r: Recipe, chef: Chef, now: Long = System.currentTimeMillis()): Int {
        var first = true
        for (e in chef.history) if (e.recipeId == r.id) { first = false; break }
        return Math.round(base(r) * multiplier(repeats(r, chef, now))).toInt() + (if (first) FIRST_BONUS else 0)
    }

    fun total(chef: Chef): Int { var s = 0; for (e in chef.history) s += e.fame; return s }

    /** Fame needed to reach [level]. */
    fun threshold(level: Int): Int = 35 * level * (level - 1)

    fun level(fame: Int): Int { var l = 1; while (fame >= threshold(l + 1)) l++; return l }

    fun rankTitle(level: Int): String = when {
        level <= 2 -> "Unknown scullion"
        level <= 4 -> "Known down the street"
        level <= 6 -> "Talk of the tavern"
        level <= 9 -> "Renowned in the village"
        level <= 13 -> "Famed across the realm"
        else -> "Legend of the kitchen"
    }

    private val SPICY = arrayOf("spic", "curry", "chili", "chilli", "piment", "épic", "epic", "harissa", "sriracha", "jalap", "hot")
    private val BAKE = arrayOf("bake", "oven", "four", "enfourn", "roast", "rôti", "gratin")
    private val GRILL = arrayOf("grill", "sear", "fry", "fried", "frire", "poêl", "saisir", "barbecue", "bbq", "griddle", "brown")
    private val MEAT = arrayOf("chicken", "beef", "pork", "lamb", "bacon", "ham", "fish", "salmon", "tuna", "shrimp", "prawn", "duck",
        "sausage", "veal", "poulet", "boeuf", "bœuf", "porc", "agneau", "lardon", "jambon", "poisson", "saumon", "thon", "crevette",
        "canard", "saucisse", "veau", "chorizo", "anchov", "steak", "mince", "turkey", "dinde")

    private fun has(s: String, words: Array<String>): Boolean { val l = s.lowercase(); for (w in words) if (l.contains(w)) return true; return false }

    private fun stepsText(v: Variant): String { val b = StringBuilder(); for (s in v.steps) b.append(s.text).append(' '); return b.toString() }
    private fun ingText(v: Variant): String { val b = StringBuilder(); for (s in v.ingredients) b.append(s.name).append(' '); return b.toString() }

    private fun variantOf(r: Recipe, name: String): Variant { for (v in r.variants) if (v.name == name) return v; return r.variant(0) }

    fun isSpicy(r: Recipe, v: Variant) = r.cls == "sorcerer" || has(v.name + " " + r.title + " " + ingText(v), SPICY)
    fun isBaked(v: Variant) = has(stepsText(v), BAKE)
    fun isGrilled(r: Recipe, v: Variant) = r.cls == "ranger" || has(stepsText(v), GRILL)
    fun isVeg(r: Recipe, v: Variant) = r.cls == "druid" || !has(ingText(v) + " " + r.title, MEAT)

    private fun score(count: Int): Int = Math.min(20, 8 + Math.floor(3 * Math.log(1.0 + count) / Math.log(2.0)).toInt())

    fun stats(chef: Chef, recipes: List<Recipe>): List<Stat> {
        var sweet = 0; var spice = 0; var bake = 0; var grill = 0; var veg = 0; var patience = 0
        for (e in chef.history) {
            val r = find(recipes, e.recipeId) ?: continue
            val v = variantOf(r, e.variant)
            if (r.dessert) sweet++
            if (isSpicy(r, v)) spice++
            if (isBaked(v)) bake++
            if (isGrilled(r, v)) grill++
            if (isVeg(r, v)) veg++
            if (r.timeMin >= 60) patience++
        }
        return listOf(Stat("SWEET", score(sweet), C.RASP), Stat("SPICE", score(spice), C.TANG), Stat("BAKE", score(bake), C.BUTTER),
            Stat("GRILL", score(grill), C.CARAMEL), Stat("VEG", score(veg), C.PIST), Stat("PATIENCE", score(patience), C.LILAC))
    }

    fun find(recipes: List<Recipe>, id: String): Recipe? { for (r in recipes) if (r.id == id) return r; return null }

    /** Kitchen weapons and the words that give them away in a recipe's steps. */
    val WEAPONS = listOf(
        Triple("whisk", "Whisk", arrayOf("whisk", "fouet", "beat", "batt", "whip", "monter", "meringue", "emulsi")),
        Triple("pan", "Pan", arrayOf("pan", "poêle", "poele", "skillet", "fry", "sauté", "saute", "sear", "casserole", "wok", "caramel")),
        Triple("knife", "Knife", arrayOf("chop", "slice", "dice", "cut", "mince", "couteau", "knife", "émince", "emince", "hach", "coupe", "tranch", "julienne", "peel", "épluch")),
        Triple("spoon", "Spoon", arrayOf("stir", "spoon", "cuillère", "cuillere", "mix", "mélang", "melang", "fold", "incorpor", "scoop")),
        Triple("ladle", "Ladle", arrayOf("ladle", "louche", "soup", "soupe", "broth", "bouillon", "stock", "simmer", "mijot", "risotto")),
        Triple("pin", "Rolling pin", arrayOf("roll out", "rolling pin", "rouleau", "étal", "etal", "pastry", "pâte", "dough", "knead", "pétri")))

    /** Weapon key -> how many cooked recipes used it. */
    fun weapons(chef: Chef, recipes: List<Recipe>): List<Pair<String, Int>> {
        val counts = IntArray(WEAPONS.size)
        for (e in chef.history) {
            val r = find(recipes, e.recipeId) ?: continue
            val sb = StringBuilder(stepsText(variantOf(r, e.variant)))
            for (k in r.equipment) sb.append(' ').append(Equipment.name(k))
            val text = sb.toString().lowercase()
            for (i in WEAPONS.indices) for (w in WEAPONS[i].third) if (text.contains(w)) { counts[i]++; break }
        }
        val out = ArrayList<Pair<String, Int>>()
        for (i in WEAPONS.indices) out.add(Pair(WEAPONS[i].first, counts[i]))
        out.sortByDescending { it.second }
        return out
    }

    fun weaponName(key: String): String { for (w in WEAPONS) if (w.first == key) return w.second; return "Spoon" }

    fun weaponIcon(key: String): String = key

    /** "Caramel Bard": flavour from the best stat, class from the most-cooked class. */
    fun classTitle(chef: Chef, recipes: List<Recipe>): String {
        if (chef.history.isEmpty()) return "Apprentice Cook"
        val counts = HashMap<String, Int>()
        for (e in chef.history) { val r = find(recipes, e.recipeId) ?: continue; counts[r.cls] = (counts[r.cls] ?: 0) + 1 }
        var best = "paladin"; var n = -1
        for ((k, v) in counts) if (v > n) { n = v; best = k }
        val st = stats(chef, recipes)
        var top = st[0]; for (s in st) if (s.score > top.score) top = s
        val flavour = when (top.key) { "SWEET" -> "Caramel"; "SPICE" -> "Chili"; "BAKE" -> "Oven"; "GRILL" -> "Ember"; "VEG" -> "Garden"; else -> "Slow-fire" }
        return "$flavour ${Classes.get(best).name}"
    }

    fun feats(chef: Chef, recipes: List<Recipe>): List<Feat> {
        var tierS = false; var spicy = 0; var comfort = 0
        val distinct = HashSet<String>()
        for (e in chef.history) {
            val r = find(recipes, e.recipeId) ?: continue
            distinct.add(r.id)
            if (r.tier == "S") tierS = true
            if (isSpicy(r, variantOf(r, e.variant))) spicy++
            if (r.cls == "cleric") comfort++
        }
        return listOf(
            Feat("spark", C.MINT, "First quest", chef.history.isNotEmpty()),
            Feat("crown", C.BUTTER, "First Tier S", tierS),
            Feat("flame", C.TANG, "Spice lord", spicy >= 5),
            Feat("heart", C.RASP, "10 comfort", comfort >= 10),
            Feat("star", C.BLUE, "30 recipes", recipes.size >= 30),
            Feat("potion", C.LILAC, "50 cooks", chef.history.size >= 50),
            Feat("d20", C.PIST, "10 recipes cooked", distinct.size >= 10),
            Feat("scroll", C.MINT, "First special", chef.specials > 0))
    }
}

/** Kitchen tools a recipe can call for. Free words are allowed too. */
object Equipment {
    class Tool(val key: String, val name: String, val icon: String, val color: Int)
    val all = listOf(
        Tool("whisk", "Whisk", "whisk", C.BUTTER), Tool("bowl", "Bowl", "bowl", C.TANG), Tool("pan", "Pan", "pan", C.CARAMEL),
        Tool("pot", "Pot", "ladle", C.BLUE), Tool("knife", "Knife", "knife", C.RASP), Tool("spoon", "Spoon", "spoon", C.MINT),
        Tool("pin", "Rolling pin", "pin", C.LILAC), Tool("oven", "Oven", "flame", C.TANG), Tool("tin", "Cake tin", "drum", C.PIST),
        Tool("tray", "Baking tray", "plate", C.BUTTER), Tool("blender", "Blender", "potion", C.MINT), Tool("grater", "Grater", "cheese", C.BUTTER),
        Tool("sieve", "Sieve", "dots", C.LILAC), Tool("scale", "Scale", "d20", C.PIST))
    fun get(key: String): Tool? { for (t in all) if (t.key == key) return t; return null }
    fun name(key: String): String = get(key)?.name ?: key
    fun icon(key: String): String = get(key)?.icon ?: "spoon"
    fun color(key: String): Int = get(key)?.color ?: C.BEIGE
}
