package com.m3uplayer.app.data

import com.m3uplayer.app.download.DownloadManager

import com.m3uplayer.app.model.MediaItem
import com.m3uplayer.app.model.MediaKind
import com.m3uplayer.app.model.PlaylistEntry
import com.m3uplayer.app.model.SeriesInfo
import java.net.URI

class LibraryGroup(val name: String) {
    var kind: MediaKind = MediaKind.LIVE

    /** Cards do grupo, na ordem da lista (episódios já agrupados em séries). */
    val items: MutableList<MediaItem> = mutableListOf()

    /** Itens tocáveis diretamente (canais/filmes), usados para anterior/próximo no player. */
    val entries: MutableList<PlaylistEntry> = mutableListOf()
}

/**
 * Organiza a lista como no Stremio: separa TV ao vivo, filmes e séries, junta episódios
 * ("Nome S01E02") numa única série e mantém os grupos na ordem em que aparecem na lista.
 */
class MediaLibrary private constructor(
    val entries: List<PlaylistEntry>,
    val groups: List<LibraryGroup>,
) {
    private val byUrl: Map<String, List<PlaylistEntry>> = entries.groupBy { it.url }
    private val byGroup: Map<String, LibraryGroup> = groups.associateBy { it.name }

    fun hasKind(kind: MediaKind) = groups.any { it.kind == kind }
    fun findByUrl(url: String): List<PlaylistEntry> = byUrl[url].orEmpty()
    fun findGroup(name: String): LibraryGroup? = byGroup[name]

    // Os downloads usam o título como nome do arquivo: assim um arquivo antigo reencontra o título da lista.
    private val byFileName: Map<String, PlaylistEntry> by lazy {
        val map = HashMap<String, PlaylistEntry>(entries.size)
        for (e in entries) map.putIfAbsent(DownloadManager.fileName(e.title, "").lowercase(), e)
        map
    }

    fun findByFileName(fileTitle: String): PlaylistEntry? = byFileName[fileTitle.lowercase()]

    fun findSeries(group: String, title: String): SeriesInfo? =
        byGroup[group.ifBlank { "Sem grupo" }]?.items?.firstOrNull { it is SeriesInfo && it.title == title } as? SeriesInfo

    /** Busca por título em canais, filmes e séries (não em episódios soltos). */
    fun search(query: String, kind: MediaKind?, limit: Int): List<MediaItem> {
        val result = ArrayList<MediaItem>()
        for (group in groups) {
            if (kind != null && group.kind != kind) continue
            for (item in group.items) {
                if (!item.title.contains(query, ignoreCase = true)) continue
                result.add(item)
                if (result.size >= limit) return result
            }
        }
        return result
    }

    companion object {
        // Procura direto o "S01E02" (o nome é o que vem antes; o resto, depois) — bem mais rápido que casar o título inteiro.
        private val episodeRegex = Regex("""\b[Ss](?<s>\d{1,3})\s*[Ee](?<e>\d{1,4})\b""")
        private val separators = charArrayOf(' ', '\t', '-', '_', '.', ':', '|')
        private val yearRegex = Regex("""[(\[](19|20)\d{2}[)\]]|\s(19|20)\d{2}\s*$""")
        private val vodExtensions = setOf(
            ".mp4", ".mkv", ".avi", ".mov", ".wmv", ".m4v", ".webm", ".flv", ".mpg", ".mpeg", ".3gp",
            ".mp3", ".aac", ".flac", ".m4a", ".ogg", ".opus", ".wav",
        )

        /** Preenche série/temporada/episódio quando o título segue "Nome S01E02". */
        fun parseEpisode(e: PlaylistEntry): Boolean {
            val match = episodeRegex.find(e.title) ?: return false
            val name = e.title.substring(0, match.range.first).trimEnd(*separators).trim()
            if (name.isEmpty()) return false
            e.kind = MediaKind.SERIES
            e.seriesName = name
            e.season = match.groups["s"]!!.value.toInt()
            e.episode = match.groups["e"]!!.value.toInt()
            e.episodeTitle = e.title.substring(match.range.last + 1).trimStart(*separators).trim().takeIf { it.isNotEmpty() }
            return true
        }

        fun build(entries: List<PlaylistEntry>): MediaLibrary {
            classify(entries)

            val groups = ArrayList<LibraryGroup>()
            val byName = HashMap<String, LibraryGroup>()
            val counts = HashMap<LibraryGroup, IntArray>()
            val series = HashMap<Pair<String, String>, SeriesInfo>()

            for (entry in entries) {
                val name = entry.displayGroup
                val group = byName.getOrPut(name) {
                    LibraryGroup(name).also { groups.add(it); counts[it] = IntArray(3) }
                }
                counts.getValue(group)[entry.kind.ordinal]++

                val seriesName = entry.seriesName
                if (entry.kind == MediaKind.SERIES && seriesName != null) {
                    val show = series.getOrPut(name to seriesName.uppercase()) {
                        SeriesInfo(seriesName, entry.group, entry.logo).also { group.items.add(it) }
                    }
                    if (show.logo == null) show.logo = entry.logo
                    show.episodes.add(entry)
                    entry.series = show
                } else {
                    group.items.add(entry)
                    group.entries.add(entry)
                }
            }

            for (show in series.values) {
                show.episodes.sortWith(compareBy({ it.season }, { it.episode }))
                show.seasons.addAll(show.episodes.map { it.season }.distinct())
            }

            for (group in groups) {
                val c = counts.getValue(group)
                val live = c[MediaKind.LIVE.ordinal]
                val movie = c[MediaKind.MOVIE.ordinal]
                val ser = c[MediaKind.SERIES.ordinal]
                group.kind = when {
                    ser >= movie && ser >= live -> MediaKind.SERIES
                    movie >= live -> MediaKind.MOVIE
                    else -> MediaKind.LIVE
                }
            }
            return MediaLibrary(entries, groups)
        }

        /**
         * Decide se cada item é canal, filme ou episódio. Listas IPTV raramente dizem isso, então combinamos pistas:
         *  • fortes: título "S01E02", "/movie/" ou "/live/" na URL, extensão de arquivo, duração no #EXTINF;
         *  • o "formato" da URL (nº de segmentos + extensão): servidores usam um formato para VOD e outro para
         *    canais, então as pistas fortes de um formato valem para os itens sem pista;
         *  • fraca: ano no título — só decide quando o formato da URL não diz nada.
         */
        private fun classify(entries: List<PlaylistEntry>) {
            val strong = BooleanArray(entries.size)
            val hasYear = BooleanArray(entries.size)
            val shapes = arrayOfNulls<String>(entries.size)
            val shapeVotes = HashMap<String, IntArray>()  // [live, movie]
            val groupVotes = HashMap<String, IntArray>()

            fun vote(map: HashMap<String, IntArray>, key: String, kind: MediaKind) {
                map.getOrPut(key) { IntArray(2) }[if (kind == MediaKind.LIVE) 0 else 1]++
            }

            for ((i, e) in entries.withIndex()) {
                val path = urlPath(e.url)
                val shape = urlShape(path)
                shapes[i] = shape

                if (parseEpisode(e)) {
                    strong[i] = true
                    vote(shapeVotes, shape, MediaKind.MOVIE) // episódio = conteúdo sob demanda
                    continue
                }

                val ext = path.substringAfterLast('/').let { f -> if ('.' in f) "." + f.substringAfterLast('.').lowercase() else "" }
                val lower = path.lowercase()
                val kind = when {
                    "/movie/" in lower || "/vod/" in lower || "/series/" in lower || ext in vodExtensions || e.duration > 0 -> MediaKind.MOVIE
                    "/live/" in lower || ext == ".ts" -> MediaKind.LIVE
                    else -> null
                }
                if (kind != null) {
                    e.kind = kind
                    strong[i] = true
                    vote(shapeVotes, shape, kind)
                    vote(groupVotes, e.displayGroup, kind)
                } else if (yearRegex.containsMatchIn(e.title)) {
                    hasYear[i] = true
                    vote(groupVotes, e.displayGroup, MediaKind.MOVIE)
                }
            }

            fun decide(map: HashMap<String, IntArray>, key: String): MediaKind? =
                map[key]?.takeIf { it[0] + it[1] > 0 }?.let { if (it[1] > it[0]) MediaKind.MOVIE else MediaKind.LIVE }

            // Se a lista tem um formato de URL claramente de VOD, um formato diferente e sem nenhuma
            // pista forte é, na prática, o dos canais ao vivo.
            val vodShapeKnown = shapeVotes.values.any { it[1] >= 50 && it[1] > it[0] }

            for ((i, e) in entries.withIndex()) {
                if (strong[i]) continue
                val shape = shapes[i]!!
                e.kind = decide(shapeVotes, shape)
                    ?: (if (vodShapeKnown && shape !in shapeVotes) MediaKind.LIVE else null)
                    ?: (if (hasYear[i]) MediaKind.MOVIE else null)
                    ?: decide(groupVotes, e.displayGroup)
                    ?: MediaKind.LIVE
            }
        }

        private fun urlPath(url: String): String = try {
            URI(url.replace(" ", "%20")).path ?: url
        } catch (_: Exception) {
            url
        }

        /** "Formato" da URL: número de segmentos + extensão (ex.: "3:" ou "2:.mp4"). */
        private fun urlShape(path: String): String {
            val segments = path.count { it == '/' }
            val file = path.substringAfterLast('/')
            val ext = if ('.' in file) "." + file.substringAfterLast('.').lowercase() else ""
            return "$segments:$ext"
        }
    }
}
