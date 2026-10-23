package com.m3uplayer.app.data

import com.m3uplayer.app.model.MediaKind
import com.m3uplayer.app.model.PlaylistEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Um item assistido, com o ponto onde o usuário parou. */
@Serializable
data class WatchRecord(
    val url: String,
    val title: String = "",
    val logo: String? = null,
    val group: String = "",
    val kind: MediaKind = MediaKind.LIVE,
    val userAgent: String? = null,
    val referrer: String? = null,
    val seriesName: String? = null,
    val season: Int = 0,
    val episode: Int = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val completed: Boolean = false,
    val lastWatched: Long = 0,
) {
    val progress: Float
        get() = if (completed) 1f else if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    /** Vale a pena oferecer "continuar de onde parou"? */
    val canResume: Boolean
        get() = !completed && durationMs > 0 && positionMs >= 10_000 && !WatchHistory.isFinished(positionMs, durationMs)

    fun toEntry() = PlaylistEntry(title = title, url = url, group = group, logo = logo, userAgent = userAgent, referrer = referrer)
        .also {
            it.kind = kind
            it.seriesName = seriesName
            it.season = season
            it.episode = episode
        }
}

/** Histórico (history.json), do mais recente para o mais antigo. Observável pelo Compose. */
class WatchHistory(private val file: File?) {
    private val _items = MutableStateFlow<List<WatchRecord>>(emptyList())
    val items: StateFlow<List<WatchRecord>> = _items.asStateFlow()

    init {
        val source = file
        if (source != null && source.exists()) {
            _items.value = runCatching { json.decodeFromString<List<WatchRecord>>(source.readText()) }.getOrDefault(emptyList())
        }
    }

    fun get(url: String): WatchRecord? = _items.value.firstOrNull { it.url == url }

    /** Registra que o item começou a ser assistido agora (sem perder a posição salva). */
    @Synchronized
    fun touch(entry: PlaylistEntry): WatchRecord {
        val old = get(entry.url)
        val record = (old ?: WatchRecord(url = entry.url)).copy(
            title = entry.title,
            logo = entry.logo ?: old?.logo,
            group = entry.group,
            kind = entry.kind,
            userAgent = entry.userAgent,
            referrer = entry.referrer,
            seriesName = entry.seriesName,
            season = entry.season,
            episode = entry.episode,
            lastWatched = System.currentTimeMillis(),
        )
        _items.value = (listOf(record) + _items.value.filter { it.url != entry.url }).take(MAX_ITEMS)
        return record
    }

    @Synchronized
    fun updatePosition(url: String, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        replace(url) {
            val finished = isFinished(positionMs, durationMs)
            it.copy(
                durationMs = durationMs,
                positionMs = if (finished) 0 else positionMs.coerceAtLeast(0), // terminado: da próxima vez começa do início
                completed = finished,
                lastWatched = System.currentTimeMillis(),
            )
        }
    }

    @Synchronized
    fun restart(url: String) = replace(url) { it.copy(positionMs = 0, completed = false) }

    @Synchronized
    fun markCompleted(url: String) = replace(url) { it.copy(completed = true, positionMs = 0) }

    @Synchronized
    fun remove(url: String) {
        _items.value = _items.value.filter { it.url != url }
    }

    @Synchronized
    fun removeSeries(seriesName: String, group: String) {
        _items.value = _items.value.filter { !(it.seriesName == seriesName && it.group == group) }
    }

    private fun replace(url: String, change: (WatchRecord) -> WatchRecord) {
        _items.value = _items.value.map { if (it.url == url) change(it) else it }
    }

    @Synchronized
    fun save() {
        val target = file ?: return
        runCatching {
            target.parentFile?.mkdirs()
            val temp = File(target.path + ".tmp")
            temp.writeText(json.encodeToString(_items.value))
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
        }
    }

    companion object {
        private const val MAX_ITEMS = 300
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** Terminou? (≥ 93% ou faltando menos de 2 minutos num vídeo longo). */
        fun isFinished(positionMs: Long, durationMs: Long): Boolean =
            durationMs > 0 && (positionMs >= durationMs * 0.93 || (durationMs > 10 * 60_000 && durationMs - positionMs < 120_000))
    }
}
