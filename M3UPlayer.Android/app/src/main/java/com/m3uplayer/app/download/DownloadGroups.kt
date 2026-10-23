package com.m3uplayer.app.download

/** Um bloco da aba Downloads: um filme/canal, ou todos os episódios baixados de uma série. */
data class DownloadGroup(
    val key: String,
    val title: String,
    val logo: String?,
    val isSeries: Boolean,
    val square: Boolean,
    val items: List<DownloadInfo>,
) {
    val summary: String
        get() {
            val done = items.count { it.state == DownloadState.COMPLETED }
            val active = items.count { it.isActive }
            val parts = mutableListOf<String>()
            if (isSeries) parts += if (items.size == 1) "1 episódio" else "${items.size} episódios"
            if (active > 0) parts += if (active == 1) "1 baixando" else "$active baixando"
            if (isSeries && done > 0) parts += if (done == 1) "1 concluído" else "$done concluídos"
            if (!isSeries && parts.isEmpty()) parts += items.firstOrNull()?.stateText.orEmpty()
            return parts.joinToString("  ·  ")
        }

    companion object {
        /** Agrupa mantendo a ordem: episódios da mesma série juntos, ordenados por temporada/episódio. */
        fun build(items: List<DownloadInfo>): List<DownloadGroup> {
            val groups = mutableListOf<DownloadGroup>()
            val series = LinkedHashMap<String, MutableList<DownloadInfo>>()
            val order = mutableListOf<Pair<String, DownloadInfo?>>() // chave da série ou item avulso, na ordem
            for (item in items) {
                val name = item.seriesName
                if (name != null) {
                    // Pela série (sem o grupo da lista): episódios baixados em outras vezes ficam juntos dos novos
                    val key = "s:${name.uppercase()}"
                    if (key !in series) {
                        series[key] = mutableListOf()
                        order += key to null
                    }
                    series.getValue(key) += item
                } else {
                    order += "i:${item.id}" to item
                }
            }
            for ((key, single) in order) {
                if (single != null) {
                    groups += DownloadGroup(key, single.title, single.logo, false, single.square, listOf(single))
                } else {
                    val episodes = series.getValue(key).sortedWith(compareBy({ it.season }, { it.episode }))
                    groups += DownloadGroup(key, episodes.first().seriesName!!, episodes.firstNotNullOfOrNull { it.logo }, true, false, episodes)
                }
            }
            return groups
        }
    }
}
