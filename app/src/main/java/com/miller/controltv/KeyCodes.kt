package com.miller.controltv

/** Android key codes understood by Google TV (same numbers as android.view.KeyEvent). */
object KeyCodes {
    const val HOME = 3
    const val BACK = 4
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val DPAD_CENTER = 23
    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val POWER = 26
    const val SPACE = 62
    const val SHIFT_LEFT = 59
    const val ENTER = 66
    const val DEL = 67
    const val MENU = 82
    const val SEARCH = 84
    const val PLAY_PAUSE = 85
    const val NEXT = 87
    const val PREVIOUS = 88
    const val REWIND = 89
    const val FAST_FORWARD = 90
    const val MUTE = 164
    const val CHANNEL_UP = 166
    const val CHANNEL_DOWN = 167
    const val GUIDE = 172
    const val SETTINGS = 176
    const val TV_INPUT = 178
    const val CAPTIONS = 175
    const val ANTENNA_CABLE = 242
    const val HDMI_1 = 243
    const val HDMI_2 = 244
    const val HDMI_3 = 245
    const val HDMI_4 = 246
    const val COMPOSITE_1 = 247
    const val COMPONENT_1 = 249
    const val ASSIST = 219

    /** Named keys a custom button can use. */
    val named: List<Pair<String, Int>> = listOf(
        "Inicio" to HOME, "Atrás" to BACK, "Arriba" to DPAD_UP, "Abajo" to DPAD_DOWN,
        "Izquierda" to DPAD_LEFT, "Derecha" to DPAD_RIGHT, "OK" to DPAD_CENTER,
        "Volumen +" to VOLUME_UP, "Volumen −" to VOLUME_DOWN, "Silencio" to MUTE,
        "Encender/Apagar" to POWER, "Play/Pausa" to PLAY_PAUSE, "Siguiente" to NEXT,
        "Anterior" to PREVIOUS, "Retroceder" to REWIND, "Adelantar" to FAST_FORWARD,
        "Canal +" to CHANNEL_UP, "Canal −" to CHANNEL_DOWN, "Guía" to GUIDE,
        "Ajustes" to SETTINGS, "Menú de entradas" to TV_INPUT, "Subtítulos" to CAPTIONS,
        "Asistente" to ASSIST, "Buscar" to SEARCH, "HDMI 1" to HDMI_1, "HDMI 2" to HDMI_2,
        "HDMI 3" to HDMI_3, "HDMI 4" to HDMI_4, "AV" to COMPOSITE_1,
        "Antena/Cable" to ANTENNA_CABLE, "Componente" to COMPONENT_1, "Enter" to ENTER,
        "Borrar" to DEL
    )

    /** Key code for a character and whether Shift is needed; null if unsupported. */
    fun forChar(ch: Char): Pair<Int, Boolean>? {
        val c = when (ch) {
            'á', 'à', 'ä', 'â' -> 'a'; 'é', 'è', 'ë', 'ê' -> 'e'; 'í', 'ì', 'ï', 'î' -> 'i'
            'ó', 'ò', 'ö', 'ô' -> 'o'; 'ú', 'ù', 'ü', 'û' -> 'u'; 'ñ' -> 'n'
            'Á' -> 'A'; 'É' -> 'E'; 'Í' -> 'I'; 'Ó' -> 'O'; 'Ú' -> 'U'; 'Ñ' -> 'N'
            else -> ch
        }
        return when (c) {
            in 'a'..'z' -> Pair(29 + (c - 'a'), false)
            in 'A'..'Z' -> Pair(29 + (c - 'A'), true)
            in '0'..'9' -> Pair(7 + (c - '0'), false)
            ' ' -> Pair(SPACE, false)
            '.' -> Pair(56, false)
            ',' -> Pair(55, false)
            '-' -> Pair(69, false)
            '\'' -> Pair(75, false)
            '/' -> Pair(76, false)
            '@' -> Pair(77, false)
            '\n' -> Pair(ENTER, false)
            else -> null
        }
    }
}
