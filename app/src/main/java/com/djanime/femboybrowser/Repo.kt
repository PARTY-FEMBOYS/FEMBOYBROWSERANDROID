package com.djanime.femboybrowser

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Link(val title: String, val url: String)

/** Закладки, история и настройки. */
class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("femboy", Context.MODE_PRIVATE)

    var theme: String
        get() = sp.getString("theme", "Sakura Bunny") ?: "Sakura Bunny"
        set(v) { sp.edit().putString("theme", v).apply() }
    var search: String
        get() = sp.getString("search", "DuckDuckGo") ?: "DuckDuckGo"
        set(v) { sp.edit().putString("search", v).apply() }
    var desktop: Boolean
        get() = sp.getBoolean("desktop", false)
        set(v) { sp.edit().putBoolean("desktop", v).apply() }

    private fun readLinks(key: String): MutableList<Link> {
        val out = mutableListOf<Link>()
        try {
            val a = JSONArray(sp.getString(key, "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Link(o.optString("title"), o.optString("url")))
            }
        } catch (e: Exception) { /* пусто */ }
        return out
    }

    private fun writeLinks(key: String, list: List<Link>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("title", it.title).put("url", it.url)) }
        sp.edit().putString(key, a.toString()).apply()
    }

    fun bookmarks(): MutableList<Link> = readLinks("bookmarks")
    fun saveBookmarks(l: List<Link>) = writeLinks("bookmarks", l)
    fun history(): MutableList<Link> = readLinks("history")
    fun saveHistory(l: List<Link>) = writeLinks("history", l.takeLast(500))
}

class BgEntry(val stem: String, val isVideo: Boolean, val assetPath: String?, val file: File?)

/** Темы и фоны: встроенные лежат в assets/, пользовательские — во внутренней папке приложения. */
class ThemeRepo(private val ctx: Context) {
    private val userThemes = File(ctx.filesDir, "themes").apply { mkdirs() }
    private val userBgs = File(ctx.filesDir, "backgrounds").apply { mkdirs() }

    private fun readTheme(text: String): Theme? = try { Theme.fromJson(JSONObject(text)) } catch (e: Exception) { null }

    fun themes(): List<Theme> {
        val map = LinkedHashMap<String, Theme>()
        (ctx.assets.list("themes") ?: emptyArray()).sorted().forEach { f ->
            if (f.endsWith(".json")) {
                val t = readTheme(ctx.assets.open("themes/$f").bufferedReader().use { it.readText() })
                if (t != null) map[t.name] = t
            }
        }
        userThemes.listFiles()?.sortedBy { it.name }?.forEach { f ->
            if (f.name.endsWith(".json")) {
                val t = readTheme(f.readText())
                if (t != null) map[t.name] = t // пользовательская тема с тем же именем перекрывает встроенную
            }
        }
        return map.values.toList().ifEmpty { listOf(Theme()) }
    }

    private fun slug(name: String) = name.trim().lowercase().replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").ifEmpty { "theme" }

    fun save(t: Theme) {
        File(userThemes, slug(t.name) + ".json").writeText(t.toJson().toString(2))
    }

    /** Удаляет только пользовательские темы. */
    fun delete(name: String): Boolean {
        var done = false
        userThemes.listFiles()?.forEach { f ->
            val t = readTheme(f.readText())
            if (t != null && t.name == name) { f.delete(); done = true }
        }
        return done
    }

    fun backgrounds(): List<BgEntry> {
        val map = LinkedHashMap<String, BgEntry>()
        val videoExt = listOf("mp4", "webm", "mkv", "mov")
        val allExt = videoExt + listOf("gif", "webp")
        fun put(stem: String, ext: String, asset: String?, file: File?) {
            val isVideo = ext in videoExt
            val prev = map[stem]
            if (prev == null || (isVideo && !prev.isVideo) || file != null) map[stem] = BgEntry(stem, isVideo, asset, file)
        }
        (ctx.assets.list("backgrounds") ?: emptyArray()).forEach { f ->
            val ext = f.substringAfterLast('.', "").lowercase()
            if (ext in allExt) put(f.substringBeforeLast('.'), ext, "backgrounds/$f", null)
        }
        userBgs.listFiles()?.forEach { f ->
            val ext = f.extension.lowercase()
            if (ext in allExt) put(f.nameWithoutExtension, ext, null, f)
        }
        return map.values.sortedWith(compareBy({ it.stem.length }, { it.stem }))
    }

    fun find(stem: String): BgEntry? = backgrounds().firstOrNull { it.stem == stem.substringBeforeLast('.') }

    /** Копирует выбранный пользователем mp4/gif в папку приложения. Возвращает имя фона или null. */
    fun addBackground(uri: Uri): String? {
        val mime = ctx.contentResolver.getType(uri) ?: ""
        val ext = when {
            mime == "video/webm" -> "webm"
            mime.startsWith("video/") -> "mp4"
            mime == "image/gif" -> "gif"
            mime == "image/webp" -> "webp"
            else -> return null
        }
        val used = backgrounds().map { it.stem }.toSet()
        var stem = "bg"
        var i = 1
        while (stem in used) { stem = "bg$i"; i++ }
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                File(userBgs, "$stem.$ext").outputStream().use { input.copyTo(it) }
            }
            stem
        } catch (e: Exception) {
            null
        }
    }
}
