package dev.shuchir.hcgateway.ui.theme

import androidx.appcompat.app.AppCompatDelegate

fun themeModeToNightMode(mode: String): Int = when (mode) {
    "light" -> AppCompatDelegate.MODE_NIGHT_NO
    "dark" -> AppCompatDelegate.MODE_NIGHT_YES
    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
}
