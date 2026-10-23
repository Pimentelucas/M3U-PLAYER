package com.m3uplayer.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m3uplayer.app.ui.components.ItemArt
import com.m3uplayer.app.model.PlaylistEntry
import com.m3uplayer.app.model.SeriesInfo
import com.m3uplayer.app.ui.MainViewModel
import com.m3uplayer.app.ui.components.Poster
import com.m3uplayer.app.ui.theme.AppColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesScreen(
    vm: MainViewModel,
    series: SeriesInfo,
    onBack: () -> Unit,
    onPlay: (PlaylistEntry) -> Unit,
    onDownloadSeason: (List<PlaylistEntry>) -> Unit,
) {
    val records by vm.history.items.collectAsState()
    val recordMap = remember(records) { records.associateBy { it.url } }
    val (target, label) = remember(series, records) { vm.seriesResumeTarget(series, records) }
    var seasonIndex by rememberSaveable(series.key) { mutableIntStateOf(series.seasons.indexOf(target.season).coerceAtLeast(0)) }
    val season = series.seasons.getOrElse(seasonIndex) { series.seasons.first() }
    val episodes = remember(series, season) { series.episodes.filter { it.season == season } }

    Scaffold(
        containerColor = AppColors.Bg,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.Bg),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(Modifier.padding(horizontal = 16.dp)) {
                    Poster(series, null, Modifier.width(120.dp), width = 120.dp)
                    Column(Modifier.padding(start = 16.dp).weight(1f)) {
                        Text(series.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("${series.displayGroup} · ${series.subtitle}", Modifier.padding(top = 6.dp),
                            color = AppColors.Muted, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { onPlay(target) }) {
                            Icon(Icons.Default.PlayArrow, null)
                            Spacer(Modifier.width(6.dp))
                            Text(label)
                        }
                        OutlinedButton(onClick = { onDownloadSeason(episodes) }, Modifier.padding(top = 6.dp)) {
                            Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Baixar temporada")
                        }
                    }
                }
            }
            if (series.seasons.size > 1) {
                item {
                    LazyRow(contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(series.seasons) { index, number ->
                            FilterChip(
                                selected = index == seasonIndex,
                                onClick = { seasonIndex = index },
                                label = { Text("Temporada $number") },
                                shape = RoundedCornerShape(16.dp),
                                border = null,
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = AppColors.Surface2, labelColor = AppColors.Text,
                                    selectedContainerColor = AppColors.Accent, selectedLabelColor = Color.White,
                                ),
                            )
                        }
                    }
                }
            } else {
                item { Spacer(Modifier.height(16.dp)) }
            }
            itemsIndexed(episodes, key = { i, ep -> "$i|${ep.url}" }) { _, episode ->
                val record = recordMap[episode.url]
                Row(
                    Modifier.fillMaxWidth().clickable { onPlay(episode) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(140.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(AppColors.Surface2)) {
                        Text("E${episode.episode}", Modifier.align(Alignment.Center), color = AppColors.Muted, fontSize = 20.sp)
                        ItemArt(episode, 200.dp, Modifier.fillMaxSize())
                        val progress = record?.progress ?: 0f
                        if (record?.completed != true && progress > 0.005f) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(6.dp).height(3.dp).clip(CircleShape),
                                trackColor = Color.Black.copy(alpha = 0.6f),
                                drawStopIndicator = {},
                            )
                        }
                    }
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(episode.episodeHeading, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(episode.episodeCode, color = AppColors.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    if (record?.completed == true) Icon(Icons.Default.CheckCircle, "Assistido", tint = AppColors.Accent)
                    IconButton(onClick = { vm.download(episode) }) { Icon(Icons.Default.Download, "Baixar episódio") }
                }
            }
        }
    }
}
