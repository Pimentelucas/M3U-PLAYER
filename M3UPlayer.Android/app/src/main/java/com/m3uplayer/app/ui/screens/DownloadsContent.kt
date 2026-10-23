package com.m3uplayer.app.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import com.m3uplayer.app.ui.components.ArtImage
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m3uplayer.app.download.DownloadGroup
import com.m3uplayer.app.download.DownloadInfo
import com.m3uplayer.app.download.DownloadState
import com.m3uplayer.app.ui.MainViewModel
import com.m3uplayer.app.ui.theme.AppColors

@Composable
fun DownloadsContent(vm: MainViewModel) {
    val items by vm.downloads.items.collectAsState()
    val folders by vm.folders.collectAsState()
    val context = LocalContext.current
    var choosingSeries by remember { mutableStateOf(false) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) vm.setDownloadFolder(choosingSeries, tree)
    }
    // Ao abrir a aba, relê as pastas (mostra também o que foi baixado em outras vezes)
    LaunchedEffect(Unit) { vm.refreshDownloadedFiles() }
    fun choose(series: Boolean) {
        choosingSeries = series
        pickFolder.launch(null)
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Downloads", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (items.any { it.state == DownloadState.FAILED || it.state == DownloadState.CANCELED }) {
                    TextButton(onClick = { vm.downloads.clearFinished() }) { Text("Limpar falhas") }
                }
            }
        }
        // Onde os arquivos são salvos (cada um com "Alterar")
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(AppColors.Surface).padding(horizontal = 14.dp, vertical = 6.dp)) {
                FolderRow("Filmes e canais", folders.movies) { choose(series = false) }
                HorizontalDivider(color = AppColors.Line)
                FolderRow("Séries", folders.series + "/<nome da série>") { choose(series = true) }
            }
        }
        if (items.isEmpty()) {
            item {
                Text(
                    "Nenhum download ainda. Toque num filme, episódio ou canal e use o botão de download.",
                    Modifier.padding(top = 24.dp, start = 8.dp, end = 8.dp), color = AppColors.Muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        // Episódios da mesma série ficam juntos, sob o pôster da série
        val groups = DownloadGroup.build(items)
        items(groups, key = { it.key }) { group ->
            DownloadGroupCard(group, onCancel = { vm.downloads.cancel(it.id) }, onOpen = { item ->
                val uri = item.openUri ?: return@DownloadGroupCard
                val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), "video/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    vm.toast("Nenhum app para abrir este arquivo")
                }
            })
        }
    }
}

@Composable
private fun FolderRow(label: String, path: String, onChange: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Text(path, color = AppColors.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onChange) { Text("Alterar pasta") }
    }
}

@Composable
private fun DownloadGroupCard(group: DownloadGroup, onCancel: (DownloadInfo) -> Unit, onOpen: (DownloadInfo) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(AppColors.Surface).padding(14.dp)) {
        Box(
            Modifier.width(72.dp).height(if (group.square) 72.dp else 108.dp).clip(RoundedCornerShape(10.dp)).background(AppColors.Surface2),
        ) {
            ArtImage(group.logo, 72.dp, Modifier.fillMaxSize().padding(if (group.square) 10.dp else 0.dp),
                contentScale = if (group.square) ContentScale.Fit else ContentScale.Crop)
        }
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(group.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(group.summary, color = AppColors.Muted, style = MaterialTheme.typography.bodySmall)
            for (item in group.items) {
                Spacer(Modifier.height(8.dp))
                DownloadRow(item, showEpisode = group.isSeries, onCancel = { onCancel(item) }, onOpen = { onOpen(item) })
            }
        }
    }
}

@Composable
private fun DownloadRow(item: DownloadInfo, showEpisode: Boolean, onCancel: () -> Unit, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showEpisode) {
                Text(item.episodeLabel ?: item.title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Spacer(Modifier.weight(1f))
            }
            Text(item.stateText, Modifier.padding(start = 8.dp), color = AppColors.Muted, style = MaterialTheme.typography.bodySmall)
        }
        val barModifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).height(5.dp).clip(CircleShape)
        if (item.isActive && item.indeterminate) {
            LinearProgressIndicator(barModifier, trackColor = AppColors.Surface2)
        } else {
            LinearProgressIndicator(progress = { item.progress }, modifier = barModifier, trackColor = AppColors.Surface2, drawStopIndicator = {})
        }
        Text(item.detail, color = AppColors.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (item.isActive || (item.openUri != null && item.fileName != null)) {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.isActive) OutlinedButton(onClick = onCancel) { Text("Parar") }
                else Button(onClick = onOpen) { Text("Abrir") }
            }
        }
    }
}
