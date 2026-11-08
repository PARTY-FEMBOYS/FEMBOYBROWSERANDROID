package com.djanime.femboybrowser

import android.graphics.Color
import org.json.JSONObject

/** Формат темы такой же, как у ПК-версии (файлы тем в папке themes) — файлы можно переносить туда-сюда. */
data class Theme(
    var name: String = "Custom",
    var background: String = "bg",
    var bg: Int = Color.parseColor("#2b1530"),
    var panel: Int = Color.parseColor("#4a2250"),
    var accent: Int = Color.parseColor("#ff8fc7"),
    var accent2: Int = Color.parseColor("#ffb3d9"),
    var text: Int = Color.parseColor("#fff0f8"),
    var textDim: Int = Color.parseColor("#e7b7d6"),
    var radius: Int = 16,
    var panelOpacity: Float = 0.72f,
    var overlayOpacity: Float = 0.30f,
    var extra: JSONObject = JSONObject() // blur, font, style_web и прочее — храним как есть
) {
    fun copyTheme(): Theme = copy(extra = JSONObject(extra.toString()))

    fun toJson(): JSONObject = JSONObject(extra.toString()).apply {
        put("name", name)
        put("background", background)
        put("bg", hex(bg))
        put("panel", hex(panel))
        put("accent", hex(accent))
        put("accent2", hex(accent2))
        put("text", hex(text))
        put("text_dim", hex(textDim))
        put("radius", radius)
        put("panel_opacity", panelOpacity.toDouble())
        put("overlay_opacity", overlayOpacity.toDouble())
    }

    companion object {
        fun hex(c: Int): String = String.format("#%06X", 0xFFFFFF and c)

        fun withAlpha(c: Int, a: Float): Int =
            (c and 0xFFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)

        private fun parse(s: String?, def: Int): Int = try {
            Color.parseColor(s)
        } catch (e: Exception) {
            def
        }

        fun fromJson(o: JSONObject): Theme {
            val d = Theme()
            val extra = JSONObject(o.toString())
            for (k in listOf("name", "background", "bg", "panel", "accent", "accent2", "text", "text_dim", "radius", "panel_opacity", "overlay_opacity")) {
                extra.remove(k)
            }
            return Theme(
                name = o.optString("name", d.name),
                background = o.optString("background", d.background),
                bg = parse(o.optString("bg"), d.bg),
                panel = parse(o.optString("panel"), d.panel),
                accent = parse(o.optString("accent"), d.accent),
                accent2 = parse(o.optString("accent2"), d.accent2),
                text = parse(o.optString("text"), d.text),
                textDim = parse(o.optString("text_dim"), d.textDim),
                radius = o.optInt("radius", d.radius),
                panelOpacity = o.optDouble("panel_opacity", d.panelOpacity.toDouble()).toFloat(),
                overlayOpacity = o.optDouble("overlay_opacity", d.overlayOpacity.toDouble()).toFloat(),
                extra = extra
            )
        }
    }
}
