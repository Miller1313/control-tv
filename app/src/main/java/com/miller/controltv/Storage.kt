package com.miller.controltv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** What a shortcut button does. */
enum class ActionType(val label: String, val hint: String) {
    YOUTUBE_SEARCH("Buscar en YouTube", "Ej: música relajante"),
    LINK("Abrir enlace", "Ej: https://www.youtube.com/watch?v=..."),
    KEY("Tecla del control", ""),
    TEXT("Escribir texto", "Texto que se escribirá en el TV"),
}

data class CustomButton(val label: String, val type: ActionType, val value: String, val color: Int)

/** A tile on the Apps page. [link] is a deep link or market://launch?id=<package>. */
data class AppShortcut(val name: String, val link: String, val color: Int)

object AppCatalog {
    fun pkg(p: String) = "market://launch?id=$p"

    val apps: List<AppShortcut> = listOf(
        AppShortcut("YouTube", "https://www.youtube.com", 0xFFFF0033.toInt()),
        AppShortcut("Netflix", "https://www.netflix.com/title", 0xFFB20710.toInt()),
        AppShortcut("Prime Video", "https://app.primevideo.com", 0xFF00A8E1.toInt()),
        AppShortcut("Disney+", "https://www.disneyplus.com", 0xFF1A3D8F.toInt()),
        AppShortcut("Spotify", pkg("com.spotify.tv.android"), 0xFF1DB954.toInt()),
        AppShortcut("YouTube Music", pkg("com.google.android.youtube.tvmusic"), 0xFFD10024.toInt()),
        AppShortcut("YouTube Kids", pkg("com.google.android.youtube.tvkids"), 0xFFE62117.toInt()),
        AppShortcut("Max", pkg("com.wbd.stream"), 0xFF002BE7.toInt()),
        AppShortcut("Twitch", pkg("tv.twitch.android.app"), 0xFF9146FF.toInt()),
        AppShortcut("Crunchyroll", "crunchyroll://", 0xFFF47521.toInt()),
        AppShortcut("Plex", "plex://", 0xFFE5A00D.toInt()),
        AppShortcut("Apple TV", pkg("com.apple.atve.androidtv.appletv"), 0xFF3A3A3C.toInt()),
        AppShortcut("Paramount+", pkg("com.cbs.ott"), 0xFF0064FF.toInt()),
        AppShortcut("Pluto TV", pkg("tv.pluto.android"), 0xFF8A7A00.toInt()),
        AppShortcut("Tubi", pkg("com.tubitv"), 0xFF7408FF.toInt()),
        AppShortcut("Kodi", pkg("org.xbmc.kodi"), 0xFF17B2E7.toInt()),
        AppShortcut("Play Store", pkg("com.android.vending"), 0xFF01875F.toInt()),
    )
}

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

    var touchpad: Boolean
        get() = prefs.getBoolean("touchpad", false)
        set(v) { prefs.edit().putBoolean("touchpad", v).apply() }

    var phoneVolumeKeys: Boolean
        get() = prefs.getBoolean("phoneVolume", true)
        set(v) { prefs.edit().putBoolean("phoneVolume", v).apply() }

    // ---- shortcut buttons (Buscar page)

    fun loadButtons(): MutableList<CustomButton> {
        val raw = prefs.getString("buttons", null) ?: return defaultButtons()
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
            defaultButtons()
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

    private fun defaultButtons(): MutableList<CustomButton> = mutableListOf(
        CustomButton("Música top", ActionType.YOUTUBE_SEARCH, "música top 2026", Palette.CHIPS[0]),
        CustomButton("Tráilers", ActionType.YOUTUBE_SEARCH, "tráiler película estreno", Palette.CHIPS[1]),
    )

    // ---- app tiles (Apps page)

    fun loadApps(): MutableList<AppShortcut> {
        val raw = prefs.getString("apps", null) ?: return AppCatalog.apps.take(5).toMutableList()
        return try {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                AppShortcut(o.getString("name"), o.getString("link"), o.optInt("color", Palette.CHIPS[0]))
            }
        } catch (_: Exception) {
            AppCatalog.apps.take(5).toMutableList()
        }
    }

    fun saveApps(list: List<AppShortcut>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.name).put("link", it.link).put("color", it.color)) }
        prefs.edit().putString("apps", arr.toString()).apply()
    }

    // ---- recent searches

    fun history(): MutableList<String> {
        val raw = prefs.getString("history", null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { arr.getString(it) }
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun addHistory(q: String) {
        val list = history()
        list.removeAll { it.equals(q, ignoreCase = true) }
        list.add(0, q)
        val arr = JSONArray()
        list.take(8).forEach { arr.put(it) }
        prefs.edit().putString("history", arr.toString()).apply()
    }

    fun clearHistory() {
        prefs.edit().remove("history").apply()
    }
}
