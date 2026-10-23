package com.m3uplayer.app.ui.components

import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.m3uplayer.app.data.Net
import com.m3uplayer.app.data.WatchRecord
import com.m3uplayer.app.download.DownloadManager
import com.m3uplayer.app.model.MediaItem
import com.m3uplayer.app.model.MediaKind
import com.m3uplayer.app.model.PlaylistEntry
import com.m3uplayer.app.model.SeriesInfo
import com.m3uplayer.app.ui.theme.AppColors
import kotlinx.coroutines.delay
import okhttp3.Call
import androidx.media3.common.MediaItem as ExoMediaItem

/**
 * Prévia estilo Netflix: tocar numa mídia abre um card grande (≈ metade da tela) que toca trechos
 * dela com som. Tocar de novo no card abre a mídia; tocar fora volta para a lista.
 * Se o usuário desligar o som, as próximas prévias abrem sem som.
 */
@Composable
fun PreviewCard(
    item: MediaItem,
    playEntry: PlaylistEntry?,
    record: WatchRecord?,
    meta: String,
    playLabel: String,
    callFactory: Call.Factory,
    initiallyMuted: Boolean,
    onMutedChange: (Boolean) -> Unit,
    otherActions: List<CardAction>,
    onPlay: () -> Unit,
    onDownload: (() -> Unit)?,
    onEpisodes: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var muted by remember { mutableStateOf(initiallyMuted) }
    var hasFrame by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val videoAlpha by animateFloatAsState(if (hasFrame) 1f else 0f, tween(400), label = "preview")

    // ~92% da largura (até 560 dp). Em pé o vídeo é mais alto (4:3) para o card ocupar cerca de
    // metade da tela; deitado, 16:9 sem passar da altura.
    val config = LocalConfiguration.current
    val portrait = config.screenHeightDp > config.screenWidthDp
    val ratio = if (portrait) 4f / 3f else 16f / 9f
    val maxWidth = min(560.dp, ((config.screenHeightDp - 190) * ratio).dp)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Toque fora do card → volta para a lista
        Box(
            Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.fillMaxWidth(0.92f).widthIn(max = maxWidth)
                    .clip(RoundedCornerShape(18.dp)).background(AppColors.Surface)
                    .clickable(onClick = onPlay), // toque no card → abre a mídia
            ) {
                Box(Modifier.fillMaxWidth().aspectRatio(ratio).background(AppColors.Surface2)) {
                    // O vídeo fica por baixo; o pôster por cima some quando o primeiro quadro aparece.
                    if (playEntry != null) {
                        PreviewVideo(playEntry, callFactory, muted, onFirstFrame = { hasFrame = true }, modifier = Modifier.fillMaxSize())
                    }
                    Box(Modifier.fillMaxSize().alpha(1f - videoAlpha).background(AppColors.Surface2)) {
                        Text(item.initials, Modifier.align(Alignment.Center), color = AppColors.Muted.copy(alpha = 0.4f),
                            fontSize = 40.sp, fontWeight = FontWeight.Bold)
                        ItemArt(
                            item, 560.dp, Modifier.fillMaxSize().padding(if (item.isSquare) 24.dp else 0.dp),
                            contentScale = if (item.isSquare) ContentScale.Fit else ContentScale.Crop,
                        )
                    }
                    Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(90.dp)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, AppColors.Surface))),
                    )
                    if (item.kind == MediaKind.LIVE) {
                        Text("● AO VIVO", Modifier.align(Alignment.TopStart).padding(12.dp), color = AppColors.Danger, fontWeight = FontWeight.Bold)
                    }
                    Row(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                        if (otherActions.isNotEmpty()) {
                            Box {
                                RoundIcon(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "Mais opções", tint = Color.White) }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    for (action in otherActions) {
                                        DropdownMenuItem(text = { Text(action.label) }, onClick = { menuOpen = false; action.onClick() })
                                    }
                                }
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                        RoundIcon(onClick = onDismiss) { Icon(Icons.Default.Close, "Fechar", tint = Color.White) }
                    }
                    RoundIcon(
                        onClick = { muted = !muted; onMutedChange(muted) },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                    ) {
                        Icon(if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            if (muted) "Ligar o som" else "Desligar o som", tint = Color.White)
                    }
                    Text(
                        item.title, Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 60.dp, bottom = 8.dp),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }

                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp)) {
                    if (meta.isNotEmpty()) {
                        Text(meta, color = AppColors.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    val progress = if (record?.canResume == true) record.progress else 0f
                    if (progress > 0f) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(4.dp).clip(CircleShape),
                            trackColor = AppColors.Surface2, drawStopIndicator = {},
                        )
                    }
                    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = onPlay, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.PlayArrow, null)
                            Spacer(Modifier.width(6.dp))
                            Text(playLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (onEpisodes != null) {
                            OutlinedButton(onClick = onEpisodes) { Text("Episódios") }
                        }
                        if (onDownload != null) {
                            FilledTonalIconButton(onClick = onDownload) { Icon(Icons.Default.Download, "Baixar") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundIcon(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    IconButton(onClick = onClick, modifier = modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f))) {
        content()
    }
}

private val SnippetPositions = floatArrayOf(0.12f, 0.33f, 0.55f, 0.75f)
private const val SNIPPET_MS = 7_000L

/**
 * Vídeo da prévia: pula por trechos a cada 7 s (canais ao vivo tocam direto).
 * Pausa quando o app vai para segundo plano e volta ao reabrir.
 */
@OptIn(UnstableApi::class)
@Composable
private fun PreviewVideo(entry: PlaylistEntry, callFactory: Call.Factory, muted: Boolean, onFirstFrame: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val player = remember(entry.url) {
        val http = OkHttpDataSource.Factory(callFactory)
            .setUserAgent(entry.userAgent?.takeIf { it.isNotBlank() } ?: Net.USER_AGENT)
            .apply { entry.referrer?.takeIf { it.isNotBlank() }?.let { setDefaultRequestProperties(mapOf("Referer" to it)) } }
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
            .build().apply {
                volume = if (muted) 0f else 1f
                setMediaItem(ExoMediaItem.Builder().setUri(entry.url).apply {
                    if (DownloadManager.looksLikeHls(entry.url)) setMimeType(MimeTypes.APPLICATION_M3U8)
                }.build())
                prepare()
                playWhenReady = true
            }
    }

    DisposableEffect(player) {
        // O vídeo só aparece depois do pulo para o primeiro trecho (não mostra a abertura, quase sempre escura).
        var jumpedToFirstSnippet = false
        var frameBeforeJump = false
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (jumpedToFirstSnippet) onFirstFrame() else frameBeforeJump = true
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && !jumpedToFirstSnippet) {
                    jumpedToFirstSnippet = true
                    if (player.isCurrentMediaItemSeekable && player.duration > 60_000) {
                        player.seekTo((player.duration * SnippetPositions[0]).toLong())
                    } else if (frameBeforeJump) {
                        onFirstFrame() // ao vivo ou curto: mostra como está
                    }
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> player.pause()
                Lifecycle.Event.ON_RESUME -> player.play()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }
    // Só conta o tempo em que o vídeo está de fato tocando: em conexão lenta cada trecho ainda
    // aparece por 7 s, em vez de o app pular de novo antes de o trecho carregar.
    LaunchedEffect(player) {
        var index = 0
        var playedMs = 0L
        while (true) {
            delay(250)
            if (player.isPlaying) playedMs += 250
            if (playedMs >= SNIPPET_MS && player.isCurrentMediaItemSeekable && player.duration > 60_000) {
                playedMs = 0
                index = (index + 1) % SnippetPositions.size
                player.seekTo((player.duration * SnippetPositions[index]).toLong())
            }
        }
    }

    // Mesmo componente do player principal (SurfaceView), que funciona em qualquer aparelho.
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                // O card é uma janela própria: a superfície do vídeo precisa ficar acima dela.
                (videoSurfaceView as? android.view.SurfaceView)?.setZOrderMediaOverlay(true)
                this.player = player
            }
        },
        modifier = modifier,
    )
}

/** Episódio que a prévia toca para uma série (o mesmo do botão principal da série). */
fun previewTargetOf(item: MediaItem, seriesTarget: (SeriesInfo) -> PlaylistEntry): PlaylistEntry? = when (item) {
    is SeriesInfo -> seriesTarget(item)
    is PlaylistEntry -> item
}
