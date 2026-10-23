package com.m3uplayer.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m3uplayer.app.data.WatchRecord
import com.m3uplayer.app.model.MediaItem
import com.m3uplayer.app.model.PlaylistEntry
import com.m3uplayer.app.model.SeriesInfo
import com.m3uplayer.app.ui.theme.AppColors

/** Ação do menu de toque longo num card. */
data class CardAction(val label: String, val onClick: () -> Unit)

val CardWidth = 136.dp

/** Pôster com logo/imagem, iniciais enquanto carrega, barra de progresso e selo de "assistido". */
@Composable
fun Poster(item: MediaItem, record: WatchRecord?, modifier: Modifier = Modifier, width: Dp = CardWidth) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .aspectRatio(if (item.isSquare) 1f else 2f / 3f)
            .clip(shape)
            .background(AppColors.Surface2),
    ) {
        Text(
            item.initials, Modifier.align(Alignment.Center),
            color = AppColors.Muted.copy(alpha = 0.5f), fontSize = 28.sp, fontWeight = FontWeight.Bold,
        )
        ItemArt(
            item, width,
            Modifier.fillMaxSize().padding(if (item.isSquare) 18.dp else 0.dp),
            contentScale = if (item.isSquare) ContentScale.Fit else ContentScale.Crop,
        )
        val watched = record?.completed == true && item is PlaylistEntry
        val progress = record?.progress ?: 0f
        if (!watched && progress > 0.005f && item is PlaylistEntry) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp).height(4.dp).clip(CircleShape),
                color = AppColors.Accent,
                trackColor = Color.Black.copy(alpha = 0.6f),
                drawStopIndicator = {},
            )
        }
        if (watched) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape).background(AppColors.Accent),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Check, "Assistido", Modifier.size(14.dp), tint = Color.White) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaCard(
    item: MediaItem,
    record: WatchRecord?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Layout minimalista: pôster grande + título; o código do episódio só quando é um episódio.
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Poster(item, record, Modifier.fillMaxWidth())
        Text(
            item.title, Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (item is PlaylistEntry && item.isEpisode) {
            Text(
                item.episodeCode, Modifier.padding(horizontal = 2.dp),
                style = MaterialTheme.typography.labelSmall, color = AppColors.Muted, maxLines = 1,
            )
        }
    }
}

/** Linha horizontal da página inicial ("Continuar assistindo" ou um grupo). */
@Composable
fun MediaRow(
    title: String,
    items: List<MediaItem>,
    records: Map<String, WatchRecord>,
    onSeeAll: (() -> Unit)?,
    onClick: (MediaItem) -> Unit,
    onLongClick: (MediaItem) -> Unit,
) {
    Column(Modifier.padding(bottom = 22.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (onSeeAll != null) {
                TextButton(onClick = onSeeAll) {
                    Text("Ver tudo", color = AppColors.Muted)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = AppColors.Muted)
                }
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(items, key = { i, item -> "$i|${item.key}" }) { _, item ->
                MediaCard(item, recordFor(item, records), { onClick(item) }, { onLongClick(item) }, Modifier.width(CardWidth))
            }
        }
    }
}

/** Grade de cards (grupo aberto, busca, "Continuar assistindo"). */
@Composable
fun MediaGrid(
    items: List<MediaItem>,
    records: Map<String, WatchRecord>,
    header: @Composable () -> Unit,
    onClick: (MediaItem) -> Unit,
    onLongClick: (MediaItem) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 128.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { header() }
        itemsIndexed(items, key = { i, item -> "$i|${item.key}" }) { _, item ->
            MediaCard(item, recordFor(item, records), { onClick(item) }, { onLongClick(item) })
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(8.dp)) }
    }
}

fun recordFor(item: MediaItem, records: Map<String, WatchRecord>): WatchRecord? = when (item) {
    is PlaylistEntry -> records[item.url]
    is SeriesInfo -> null
}
