package com.miller.controltv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** What a custom button does. */
enum class ActionType(val label: String, val hint: String) {
    YOUTUBE_SEARCH("Buscar en YouTube", "Ej: música relajante"),
    LINK("Abrir enlace / app", "Ej: https://www.youtube.com/watch?v=..."),
    KEY("Tecla del control", ""),
    TEXT("Escribir texto", "Texto que se escribirá en el TV"),
}

data class CustomButton(val label: String, val type: ActionType, val value: String, val color: Int)

class Storage(context: Context) {
    private val prefs = context.getSharedPreferences("controltv", Context.MODE_PRIVATE)

    var lastDevice: TvDevice?
        get() {
            val host = prefs.getString("host", null) ?: return null
            return TvDevice(prefs.getString("name", host) ?: host, host, prefs.getInt("port", 6466))
        }
        set(d) {
            val e = prefs.edit()
            if (d == null) {
                e.remove("host"); e.remove("name"); e.remove("port")
            } else {
                e.putString("host", d.host); e.putString("name", d.name); e.putInt("port", d.port)
            }
            e.apply()
        }

    fun loadButtons(): MutableList<CustomButton> {
        val raw = prefs.getString("buttons", null) ?: return defaults()
        return try {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                CustomButton(
                    o.getString("label"),
                    ActionType.valueOf(o.getString("type")),
                    o.getString("value"),
                    o.optInt("color", Palette.CHIPS[0])
                )
            }
        } catch (_: Exception) {
            defaults()
        }
    }

    fun saveButtons(list: List<CustomButton>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("label", it.label).put("type", it.type.name)
                .put("value", it.value).put("color", it.color))
        }
        prefs.edit().putString("buttons", arr.toString()).apply()
    }

    private fun defaults(): MutableList<CustomButton> = mutableListOf(
        CustomButton("Música top", ActionType.YOUTUBE_SEARCH, "música top 2026", Palette.CHIPS[0]),
        CustomButton("Tráilers", ActionType.YOUTUBE_SEARCH, "tráiler película estreno", Palette.CHIPS[1]),
        CustomButton("Menú entradas", ActionType.KEY, KeyCodes.TV_INPUT.toString(), Palette.CHIPS[2]),
    )
}
