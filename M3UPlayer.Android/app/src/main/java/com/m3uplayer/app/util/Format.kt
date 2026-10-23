package com.m3uplayer.app.util

import java.util.Locale

object Format {
    private val units = arrayOf("B", "KB", "MB", "GB", "TB")
    private val ptBR = Locale.forLanguageTag("pt-BR")

    fun bytes(bytes: Long): String {
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024
            unit++
        }
        return if (unit == 0) "$bytes B"
        else String.format(ptBR, if (value >= 100) "%.0f %s" else "%.1f %s", value, units[unit])
    }

    fun duration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(ptBR, "%d:%02d:%02d", h, m, s) else String.format(ptBR, "%02d:%02d", m, s)
    }

    fun count(n: Int): String = String.format(ptBR, "%,d", n)
}
