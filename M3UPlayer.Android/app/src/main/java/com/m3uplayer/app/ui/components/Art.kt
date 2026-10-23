package com.m3uplayer.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import com.m3uplayer.app.data.Artwork
import com.m3uplayer.app.model.MediaItem

/**
 * Imagem da própria lista (tvg-logo), carregada pelo Coil (cache em memória e em disco) no tamanho
 * que o lugar precisa. Se não carregar, ficam as iniciais que estão por baixo.
 */
@Composable
fun ArtImage(
    url: String?,
    width: Dp,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    if (url.isNullOrBlank()) return
    val widthPx = with(LocalDensity.current) { width.roundToPx() }
    AsyncImage(
        model = Artwork.optimize(url, widthPx),
        contentDescription = null,
        modifier = modifier,
        contentScale = contentScale,
    )
}

/** Imagem de um card. */
@Composable
fun ItemArt(item: MediaItem, width: Dp, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) =
    ArtImage(item.logo, width, modifier, contentScale)
