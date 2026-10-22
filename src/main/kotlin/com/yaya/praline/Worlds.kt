package com.yaya.praline

import android.content.Context
import java.io.File

/**
 * Every world the app knows: the ones bundled in assets/worlds/ first, then the ones opened from a file
 * (filesDir/worlds/<id>.praline-world). A world installed from a file with a bundled world's id replaces it,
 * so a repainted Sunforge Sands can be tried without a new build. Read once, kept until a new pack comes in.
 */
object Worlds {
    /** The worlds that ship inside the app, in their order on the Missions page. */
    val BUNDLED = arrayOf("sunforge")
    private var ctx: Context? = null
    private var cache: List<WorldPack.World>? = null

    fun init(c: Context) { ctx = c.applicationContext ?: c }

    fun all(): List<WorldPack.World> {
        cache?.let { return it }
        val c = ctx ?: return emptyList()
        val byId = LinkedHashMap<String, WorldPack.World>()
        for (id in BUNDLED) try { byId[id] = WorldPack.load(WorldPack.AssetSource(c, "worlds/$id")) } catch (e: Exception) { }
        val files = WorldPack.dir(c).listFiles() ?: arrayOf()
        files.sortBy { it.lastModified() }
        for (f in files) {
            if (!f.name.endsWith(WorldPack.EXT)) continue
            try { val w = WorldPack.load(WorldPack.ZipSource(f)); byId[w.id] = w } catch (e: Exception) { }
        }
        val out = ArrayList(byId.values)
        cache = out
        return out
    }

    fun get(id: String): WorldPack.World? { for (w in all()) if (w.id == id) return w; return null }

    /** Forgets what was read, after a pack is added or replaced. */
    fun reload() { for (w in cache ?: emptyList()) w.source.close(); cache = null }

    fun recipeId(world: String, spot: String) = "mission-$world-$spot"

    /**
     * A recipe spot's recipe as it joins the grimoire: the first recipe of its .praline file, with the fixed id
     * [recipeId] (both phones add the same one), the world set, no dates, and its pictures copied into photos/ under
     * stable names ("mw-<world>-<spot>-<name>"), so the two phones agree on them too. Null if it can't be read.
     */
    fun recipe(c: Context?, w: WorldPack.World, spot: WorldPack.Spot): Recipe? {
        if (spot.story) return null
        val sr = WorldPack.spotRecipe(w.source, spot.recipe) ?: return null
        val o = sr.json
        val rename = HashMap<String, String>()
        for ((name, bytes) in sr.pictures) {
            val to = "mw-${w.id}-${spot.id}-$name"
            rename[name] = to
            if (c != null) try { val f = Store.photoFile(c, to); if (!f.exists() || f.length() != bytes.size.toLong()) f.writeBytes(bytes) } catch (e: Exception) { }
        }
        o.put("id", recipeId(w.id, spot.id))
        o.put("photo", rename[o.optString("photo")] ?: ""); o.put("orig", rename[o.optString("orig")] ?: "")
        o.put("cooked", 0L); o.put("created", 0L); o.put("updated", 0L); o.put("world", w.id)
        val vs = o.optJSONArray("variants")
        if (vs != null) for (i in 0 until vs.length()) {
            val st = vs.optJSONObject(i)?.optJSONArray("steps") ?: continue
            for (k in 0 until st.length()) {
                val s = st.optJSONObject(k) ?: continue
                s.put("h", rename[s.optString("h")] ?: ""); s.remove("vd")   // packs carry pictures, not clips
            }
        }
        return try { Recipe.fromJson(o) } catch (e: Exception) { null }
    }

    /** Where an installed pack lives (for tests and the Settings list). */
    fun file(c: Context, id: String) = File(WorldPack.dir(c), id + WorldPack.EXT)
}
