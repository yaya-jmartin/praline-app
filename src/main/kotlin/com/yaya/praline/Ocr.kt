package com.yaya.praline

import android.content.Context
import android.graphics.Bitmap

/** Reads the text in a picture. The GitHub build plugs in Google's on-device reader ([MlKitReader]). */
interface TextReader {
    fun read(c: Context, b: Bitmap, done: (String?) -> Unit)
}

/**
 * Reading a recipe from a photo of a cookbook page (1.4). The text is read on the phone, then split into a
 * title, ingredients and steps; the editor opens with them so the chef checks and fixes before saving.
 */
object Ocr {
    /** The reader in this build, or null where it isn't included (the hand-made build). Tests can set it. */
    var reader: TextReader? = try { Class.forName("com.yaya.praline.MlKitReader").getDeclaredConstructor().newInstance() as TextReader } catch (e: Throwable) { null }
    val available get() = reader != null

    private val INGREDIENTS = Regex("""^\s*(ingredients?|ingrédients?|you will need|what you need)\s*:?\s*$""", RegexOption.IGNORE_CASE)
    private val METHOD = Regex("""^\s*(method|steps?|directions?|instructions?|préparation|preparation|recette)\s*:?\s*$""", RegexOption.IGNORE_CASE)
    private val SERVES = Regex("""(?:serves|pour|for)\s+(\d{1,2})""", RegexOption.IGNORE_CASE)
    private val MINUTES = Regex("""(\d{1,3})\s*(?:min|minutes|mn)\b""", RegexOption.IGNORE_CASE)

    /** A new recipe from the text of a page: the first short line is the title; headings split the rest when there are any. */
    fun parse(text: String): Recipe {
        val lines = text.replace("\r", "").split("\n").map { it.trim() }
        val r = Recipe()
        val firstIdx = lines.indexOfFirst { it.isNotEmpty() && !INGREDIENTS.matches(it) && !METHOD.matches(it) }
        val title = if (firstIdx >= 0 && lines[firstIdx].length <= 60) lines[firstIdx] else ""
        r.title = title
        SERVES.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { if (it in 1..24) r.servings = it }
        val rest = lines.filterIndexed { i, _ -> !(i == firstIdx && title.isNotEmpty()) }
        val ing = ArrayList<String>(); val method = StringBuilder()
        val hi = rest.indexOfFirst { INGREDIENTS.matches(it) }; val hm = rest.indexOfFirst { METHOD.matches(it) }
        if (hi >= 0 || hm >= 0) {
            // headed page: ingredients between their heading and the method's, steps after
            val ingEnd = if (hm > hi) hm else rest.size
            for (i in rest.indices) {
                val l = rest[i]
                when {
                    INGREDIENTS.matches(l) || METHOD.matches(l) -> {}
                    hi >= 0 && i > hi && i < ingEnd -> if (l.isNotEmpty()) ing.add(clean(l))
                    hm >= 0 && i > hm -> method.append(l).append('\n')
                    hm < 0 && i > ingEnd -> method.append(l).append('\n')
                    hi < 0 && i < hm && l.isNotEmpty() && looksLikeIngredient(l) -> ing.add(clean(l))
                }
            }
        } else {
            // no headings: lines that start with an amount are ingredients, the rest is the method
            for (l in rest) {
                if (l.isNotEmpty() && looksLikeIngredient(l) && method.isBlank()) ing.add(clean(l))
                else method.append(l).append('\n')
            }
        }
        val v = r.variant(0)
        for (l in ing) v.ingredients.add(Ingredient.parse(l))
        for (s in StepImport.split(method.toString())) v.steps.add(Step(s))
        val mins = MINUTES.findAll(text).sumOf { it.groupValues[1].toInt() }
        if (mins in 5..600) r.timeMin = mins
        return r
    }

    private fun clean(l: String) = l.trimStart('-', '•', '*', '·', '–').trim()

    fun looksLikeIngredient(l: String): Boolean {
        val t = clean(l)
        if (t.length > 70 || t.endsWith(".") && t.split(" ").size > 6) return false
        return Ingredient.parse(t).qty != null
    }

    /** Photo → text → a new recipe in the editor. */
    fun start(a: MainActivity) {
        if (reader == null) return
        a.pickPhoto("Photo of the recipe") { f -> if (f != null) readFile(a, f) }
    }

    fun readFile(a: MainActivity, f: java.io.File) {
        val rd = reader ?: return
        run {
            a.toast("Reading the page…")
            Thread(object : Runnable {
                override fun run() {
                    val b = try { Filters.load(f, 2000) } catch (e: Throwable) { null }
                    Photos.post {
                        if (b == null) { a.toast("Couldn't open that picture"); return@post }
                        rd.read(a, b) { text ->
                            Photos.post {
                                if (text.isNullOrBlank()) { a.toast("No text found. Try a sharper, well-lit photo."); return@post }
                                val r = parse(text)
                                a.push(EditorScreen(a, null, draft = r))
                                a.toast("Check what was read before saving")
                            }
                        }
                    }
                }
            }).start()
        }
    }
}
