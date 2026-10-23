package com.m3uplayer.app.data

/**
 * A maioria das listas usa imagens do TMDB em tamanhos enormes (w1280, 600x900) para cards pequenos.
 * Aqui a URL é trocada pelo tamanho que o card precisa — a mesma imagem, de 4 a 7 vezes menor.
 */
object Artwork {
    private const val TMDB = "image.tmdb.org/t/p/"

    /** Mesma imagem no tamanho mais próximo de [widthPx] (só para o TMDB). */
    fun optimize(url: String, widthPx: Int): String {
        var start = url.indexOf(TMDB, ignoreCase = true)
        if (start < 0) return url
        start += TMDB.length
        val end = url.indexOf('/', start)
        if (end < 0) return url
        val size = when {
            widthPx <= 185 -> "w185"
            widthPx <= 342 -> "w342"
            widthPx <= 500 -> "w500"
            else -> "w780"
        }
        return url.substring(0, start) + size + url.substring(end)
    }
}
