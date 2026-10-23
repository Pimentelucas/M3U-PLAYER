package com.m3uplayer.app.data

import android.content.Context
import kotlinx.serialization.json.Json

/** Configurações (SharedPreferences): lista ativa e listas salvas. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Lista ativa: carregada automaticamente ao abrir o app. */
    var lastSource: String?
        get() = prefs.getString("lastSource", null)
        private set(value) = prefs.edit().putString("lastSource", value).apply()

    /** Listas usadas recentemente (a mais recente primeiro). */
    var savedSources: List<String>
        get() = prefs.getString("savedSources", null)
            ?.let { runCatching { Json.decodeFromString<List<String>>(it) }.getOrNull() }
            .orEmpty()
        private set(value) = prefs.edit().putString("savedSources", Json.encodeToString(value)).apply()

    /** Depois desse tempo a lista é atualizada em segundo plano ao abrir o app. */
    val refreshHours: Int get() = prefs.getInt("refreshHours", 12)

    /** Servidores cujo certificado HTTPS o usuário escolheu aceitar (ver [Tls]). Consultado a cada requisição. */
    @Volatile
    var trustedHosts: Set<String> = prefs.getString("trustedHosts", null)
        ?.let { runCatching { Json.decodeFromString<List<String>>(it) }.getOrNull() }
        .orEmpty().toSet()
        private set

    fun trustHost(host: String) = saveTrusted(trustedHosts + host.lowercase())

    fun untrustHost(host: String) = saveTrusted(trustedHosts - host.lowercase())

    private fun saveTrusted(hosts: Set<String>) {
        trustedHosts = hosts
        prefs.edit().putString("trustedHosts", Json.encodeToString(hosts.sorted())).apply()
    }

    /** A prévia abre com som; se o usuário desligar numa prévia, as próximas abrem sem som. */
    var previewMuted: Boolean
        get() = prefs.getBoolean("previewMuted", false)
        set(value) = prefs.edit().putBoolean("previewMuted", value).apply()

    /** Pastas escolhidas para os downloads (URIs do seletor de pastas); null = Downloads/M3U Player. */
    var moviesFolder: String?
        get() = prefs.getString("moviesFolder", null)
        set(value) = prefs.edit().putString("moviesFolder", value).apply()

    /** Pasta das séries; null = a mesma dos filmes. */
    var seriesFolder: String?
        get() = prefs.getString("seriesFolder", null)
        set(value) = prefs.edit().putString("seriesFolder", value).apply()

    /** Fim da abertura aprendido por série (ms), usado pelo botão "Pular abertura". */
    val introEnds: MutableMap<String, Long> by lazy {
        prefs.getString("introEnds", null)
            ?.let { runCatching { Json.decodeFromString<Map<String, Long>>(it) }.getOrNull() }
            .orEmpty().toMutableMap()
    }

    fun saveIntroEnds() = prefs.edit().putString("introEnds", Json.encodeToString(introEnds.toMap())).apply()

    fun rememberSource(source: String) {
        lastSource = source
        savedSources = (listOf(source) + savedSources.filter { !it.equals(source, ignoreCase = true) }).take(10)
    }

    fun forgetSource(source: String) {
        val remaining = savedSources.filter { !it.equals(source, ignoreCase = true) }
        savedSources = remaining
        if (lastSource.equals(source, ignoreCase = true)) lastSource = remaining.firstOrNull()
    }
}
