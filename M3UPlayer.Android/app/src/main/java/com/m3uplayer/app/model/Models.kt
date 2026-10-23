package com.m3uplayer.app.model

enum class MediaKind { LIVE, MOVIE, SERIES }

/** Algo que aparece como um card na biblioteca: canal, filme ou série. */
sealed class MediaItem {
    abstract val title: String
    abstract val logo: String?
    abstract val group: String
    abstract val kind: MediaKind

    val displayGroup: String get() = group.ifBlank { "Sem grupo" }

    /** Canais ao vivo usam card quadrado (logo); filmes e séries, pôster 2:3. */
    val isSquare: Boolean get() = kind == MediaKind.LIVE

    open val subtitle: String get() = displayGroup

    /** Letras exibidas enquanto o logo não carrega (ou quando não existe). */
    val initials: String
        get() = title.split(' ', '-', '_', '.', ':', '|', '[', '(')
            .filter { it.isNotEmpty() && it[0].isLetterOrDigit() }
            .take(2)
            .joinToString("") { it[0].uppercase() }
            .ifEmpty { "?" }

    /** Chave estável para listas do Compose. */
    abstract val key: String
}

/** Um item de uma lista M3U (canal, filme, episódio...). */
class PlaylistEntry(
    override var title: String = "",
    var url: String = "",
    override var group: String = "",
    override var logo: String? = null,
    var tvgId: String? = null,
    /** Duração declarada no #EXTINF (segundos); -1 normalmente indica stream ao vivo. */
    var duration: Double = -1.0,
    var userAgent: String? = null,
    var referrer: String? = null,
) : MediaItem() {
    override var kind: MediaKind = MediaKind.LIVE

    // Preenchidos quando o título segue o padrão "Nome S01E02"
    var seriesName: String? = null
    var season: Int = 0
    var episode: Int = 0
    var episodeTitle: String? = null
    var series: SeriesInfo? = null

    val isEpisode: Boolean get() = seriesName != null
    val episodeCode: String get() = "T$season E$episode"
    val episodeHeading: String
        get() = episodeTitle?.takeIf { it.isNotBlank() }?.let { "$episode. $it" } ?: "Episódio $episode"

    override val subtitle: String get() = if (isEpisode) "$seriesName · $episodeCode" else displayGroup
    override val key: String get() = "e:$group|$url|$title"

    override fun toString() = title
}

/** Série montada a partir dos episódios de um mesmo grupo. */
class SeriesInfo(
    override val title: String,
    override val group: String,
    override var logo: String?,
) : MediaItem() {
    override val kind: MediaKind get() = MediaKind.SERIES
    val episodes: MutableList<PlaylistEntry> = mutableListOf()
    val seasons: MutableList<Int> = mutableListOf()

    override val subtitle: String
        get() = if (seasons.size == 1) "${episodes.size} episódios" else "${seasons.size} temporadas · ${episodes.size} episódios"
    override val key: String get() = "s:$group|$title"

    fun nextAfter(episode: PlaylistEntry): PlaylistEntry? {
        val index = episodes.indexOf(episode)
        return if (index >= 0 && index + 1 < episodes.size) episodes[index + 1] else null
    }
}
