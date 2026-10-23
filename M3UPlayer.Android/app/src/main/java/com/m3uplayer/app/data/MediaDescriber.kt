package com.m3uplayer.app.data

import com.m3uplayer.app.model.MediaItem
import com.m3uplayer.app.model.MediaKind
import com.m3uplayer.app.model.PlaylistEntry
import com.m3uplayer.app.model.SeriesInfo
import com.m3uplayer.app.util.Format
import java.util.Locale

/** Texto da prévia. Listas M3U não trazem sinopse, então o resumo é montado com o que há. */
data class MediaDescription(val meta: String, val text: String, val playLabel: String)

object MediaDescriber {
    private val yearRegex = Regex("""[(\[](19|20)\d{2}[)\]]|\b(19|20)\d{2}\b""")
    private val qualityRegex = Regex("""\b(4K|UHD|FHD|FULL\s?HD|HD|SD|H\.?265|HEVC)\b""", RegexOption.IGNORE_CASE)
    private val subtitledRegex = Regex("""\[L]|\bLEG(ENDADO|ENDADA|ENDADOS|ENDADAS)?\b""", RegexOption.IGNORE_CASE)
    private val dubbedRegex = Regex("""\bDUB(LADO|LADA|LADOS)?\b""", RegexOption.IGNORE_CASE)
    private val ptBR = Locale.forLanguageTag("pt-BR")

    /**
     * @param record histórico do item (filme/canal) ou do episódio-alvo (série).
     * @param seriesTarget para séries: o episódio que o botão vai tocar.
     */
    fun describe(item: MediaItem, record: WatchRecord?, seriesTarget: PlaylistEntry? = null): MediaDescription {
        val source = item.title + " " + item.group
        val year = yearRegex.find(item.title)?.value?.trim('(', ')', '[', ']')
        val quality = quality(source)
        val language = when {
            subtitledRegex.containsMatchIn(source) -> "Legendado"
            dubbedRegex.containsMatchIn(source) -> "Dublado"
            else -> null
        }
        val genre = cleanGroup(item.group)
        val kind = when {
            item is SeriesInfo -> "Série"
            item is PlaylistEntry && item.isEpisode -> "Episódio"
            item.kind == MediaKind.LIVE -> "Ao vivo"
            else -> "Filme"
        }
        val meta = listOfNotNull(year, kind, quality, language).joinToString("  ·  ")

        val text = StringBuilder()
        var playLabel = "Assistir"
        when {
            item is SeriesInfo -> {
                text.append(if (item.seasons.size == 1) "Série com ${item.episodes.size} episódios"
                else "Série com ${item.seasons.size} temporadas e ${item.episodes.size} episódios")
                if (genre.isNotEmpty()) text.append(", em $genre")
                text.append('.')
                if (seriesTarget != null) {
                    when {
                        record?.canResume == true -> {
                            text.append(" Você parou em ${Format.duration(record.positionMs)} de ${seriesTarget.episodeCode}.")
                            playLabel = "Continuar ${seriesTarget.episodeCode}"
                        }
                        record != null || seriesTarget !== item.episodes.first() -> {
                            text.append(" Próximo: ${seriesTarget.episodeCode}.")
                            playLabel = "Assistir ${seriesTarget.episodeCode}"
                        }
                        else -> playLabel = "Assistir ${seriesTarget.episodeCode}"
                    }
                }
            }
            item.kind == MediaKind.LIVE -> {
                text.append("Canal ao vivo")
                if (quality != null) text.append(" em $quality")
                if (genre.isNotEmpty()) text.append(", do grupo $genre")
                text.append('.')
            }
            item is PlaylistEntry -> {
                text.append(if (item.isEpisode) "${item.seriesName}, ${item.episodeCode}" else "Filme")
                if (!item.isEpisode && genre.isNotEmpty()) text.append(" de $genre")
                if (!item.isEpisode && year != null) text.append(", de $year")
                text.append('.')
                when {
                    record?.canResume == true -> {
                        text.append(" Você parou em ${Format.duration(record.positionMs)} de ${Format.duration(record.durationMs)}.")
                        playLabel = "Continuar"
                    }
                    record?.completed == true -> {
                        text.append(" Você já assistiu.")
                        playLabel = "Assistir de novo"
                    }
                    record != null && record.durationMs > 0 -> text.append(" Duração: ${Format.duration(record.durationMs)}.")
                }
            }
        }
        return MediaDescription(meta, text.toString(), playLabel)
    }

    private fun quality(text: String): String? {
        val found = qualityRegex.findAll(text).map { it.value.uppercase().replace(" ", "") }.toSet()
        return when {
            "4K" in found || "UHD" in found -> "4K"
            "FHD" in found || "FULLHD" in found -> "Full HD"
            "H265" in found || "H.265" in found || "HEVC" in found -> "H.265"
            "HD" in found -> "HD"
            "SD" in found -> "SD"
            else -> null
        }
    }

    /** "♦️ CANAIS | FHD H265 ⭐" → "Canais · FHD H265"; "♦️ Ação ✔️" → "Ação". */
    fun cleanGroup(group: String): String = group.split('|')
        .map { part -> part.filter { it.isLetterOrDigit() || it in " &+-'" }.trim() }
        .filter { it.isNotEmpty() }
        .map { part ->
            val allCaps = part.length > 3 && part.any { it.isLetter() } && part.all { !it.isLetter() || it.isUpperCase() }
            if (allCaps && !qualityRegex.containsMatchIn(part))
                part.lowercase(ptBR).split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(ptBR) } }
            else part
        }
        .joinToString(" · ")
}
