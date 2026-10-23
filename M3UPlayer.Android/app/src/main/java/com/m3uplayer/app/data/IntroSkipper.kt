package com.m3uplayer.app.data

import com.m3uplayer.app.model.PlaylistEntry

/**
 * Botão "Pular abertura". Listas M3U não dizem onde a abertura termina, então:
 *  • sem informação, o botão pula para 1:30 (duração típica de abertura);
 *  • o app aprende o fim real da abertura de cada série: se, logo depois de pular, o usuário
 *    ajusta a posição, ou se ele mesmo arrasta a barra para frente no começo do episódio,
 *    aquela posição passa a ser usada nos próximos episódios da série.
 * (Mesma regra da versão Windows.)
 */
class IntroSkipper(private val learned: MutableMap<String, Long>, private val onLearned: () -> Unit = {}) {
    private var skippedUrl: String? = null
    private var skippedAt = 0L

    fun isLearned(e: PlaylistEntry) = keyFor(e) in learned

    fun targetFor(e: PlaylistEntry): Long = learned[keyFor(e)] ?: DEFAULT_INTRO_END_MS

    /** Mostra o botão no começo de episódios (não em filmes, canais ou vídeos curtos). */
    fun shouldShow(entry: PlaylistEntry?, positionMs: Long, lengthMs: Long): Boolean =
        entry != null && entry.isEpisode && lengthMs >= 5 * 60_000 &&
            positionMs >= SHOW_FROM_MS && positionMs < targetFor(entry) - 3_000 && positionMs < lengthMs / 3

    /** O usuário tocou em "Pular abertura": devolve para onde ir. */
    fun skip(entry: PlaylistEntry, nowMs: Long): Long {
        skippedUrl = entry.url
        skippedAt = nowMs
        return targetFor(entry)
    }

    /** Mudança manual de posição. Devolve o novo fim de abertura aprendido (ou null). */
    fun onManualSeek(entry: PlaylistEntry?, fromMs: Long, toMs: Long, nowMs: Long): Long? {
        if (entry == null || !entry.isEpisode || toMs < MIN_INTRO_END_MS || toMs > MAX_INTRO_END_MS) return null
        val adjustingSkip = skippedUrl == entry.url && nowMs - skippedAt <= ADJUST_WINDOW_MS
        val skippedByHand = fromMs < 120_000 && toMs - fromMs >= 15_000
        if (!adjustingSkip && !skippedByHand) return null

        skippedAt = nowMs // permite ajustes finos seguidos (ex.: vários -10 s)
        skippedUrl = entry.url
        val rounded = toMs / 1000 * 1000
        learned[keyFor(entry)] = rounded
        onLearned()
        return rounded
    }

    companion object {
        const val DEFAULT_INTRO_END_MS = 90_000L
        private const val SHOW_FROM_MS = 3_000L
        private const val ADJUST_WINDOW_MS = 30_000L
        private const val MIN_INTRO_END_MS = 20_000L
        private const val MAX_INTRO_END_MS = 6 * 60_000L

        fun keyFor(e: PlaylistEntry) = "${e.group}\u0001${e.seriesName}"
    }
}
