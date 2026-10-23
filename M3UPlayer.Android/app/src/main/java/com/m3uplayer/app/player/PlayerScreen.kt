package com.m3uplayer.app.player

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import com.m3uplayer.app.ui.components.ArtImage
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.m3uplayer.app.ui.theme.AppColors
import com.m3uplayer.app.util.Format
import kotlinx.coroutines.delay

/** Estado da tela do player, atualizado pela PlayerActivity. */
class PlayerUiState {
    var title by mutableStateOf("")
    var subtitle by mutableStateOf("")
    var isPlaying by mutableStateOf(false)
    var buffering by mutableStateOf(true)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var isLive by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    /** Servidor com certificado não reconhecido (mostra o botão "Confiar neste servidor"). */
    var certHost by mutableStateOf<String?>(null)
    var hasPrev by mutableStateOf(false)
    var hasNext by mutableStateOf(false)
    var resumeFrom by mutableLongStateOf(0L)
    var toast by mutableStateOf<String?>(null)
    var toastId by mutableIntStateOf(0)
    var resizeMode by mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT)
    /** O aparelho tem Google Cast (Play Services). */
    var castAvailable by mutableStateOf(false)
    /** Nome da TV para onde o vídeo está sendo transmitido (null = tocando no celular). */
    var castDevice by mutableStateOf<String?>(null)
    /** Mostrar o botão "Pular abertura" agora. */
    var showSkipIntro by mutableStateOf(false)
    /** Fim do episódio: próximo episódio e segundos que faltam para ele começar (null = sem contagem). */
    var nextUpTitle by mutableStateOf<String?>(null)
    var nextUpLogo by mutableStateOf<String?>(null)
    var nextUpSeconds by mutableIntStateOf(0)

    fun showToast(text: String) {
        toast = text
        toastId++
    }
}

interface PlayerActions {
    fun back()
    fun togglePlay()
    fun seekBy(deltaMs: Long)
    fun seekTo(positionMs: Long)
    fun previous()
    fun next()
    fun startOver()
    fun download()
    fun retry()
    fun trustServer()
    fun castToTv()
    fun stopCasting()
    fun mirrorScreen()
    fun skipIntro()
    fun playNextUp()
    fun cancelNextUp()
    fun toggleResize()
}

private const val HIDE_AFTER_MS = 3000L

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(player: Player, ui: PlayerUiState, actions: PlayerActions) {
    var controlsVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var seekFlash by remember { mutableStateOf<String?>(null) }
    var showResume by remember(ui.resumeFrom) { mutableStateOf(ui.resumeFrom > 0) }
    fun touch() { controlsVisible = true; interaction++ }

    BackHandler { actions.back() }

    // Os controles somem 3 s depois da última interação, enquanto o vídeo está tocando.
    // Transmitindo para a TV, os controles ficam sempre visíveis (o celular vira controle remoto).
    LaunchedEffect(controlsVisible, ui.isPlaying, interaction, dragging, ui.error, ui.castDevice) {
        if (controlsVisible && ui.isPlaying && !dragging && ui.error == null && ui.castDevice == null && ui.nextUpTitle == null) {
            delay(HIDE_AFTER_MS)
            controlsVisible = false
        }
    }
    LaunchedEffect(ui.isPlaying) { if (!ui.isPlaying) controlsVisible = true }
    LaunchedEffect(ui.resumeFrom) { if (ui.resumeFrom > 0) { delay(8000); showResume = false } }
    LaunchedEffect(seekFlash) { if (seekFlash != null) { delay(700); seekFlash = null } }
    LaunchedEffect(ui.toastId) { if (ui.toast != null) { delay(3000); ui.toast = null } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    useController = false
                    this.player = player
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    keepScreenOn = true
                }
            },
            update = { it.resizeMode = ui.resizeMode },
            modifier = Modifier.fillMaxSize(),
        )

        // Toque: mostra/esconde os controles. Toque duplo na metade esquerda/direita: -10 s / +10 s.
        Box(
            Modifier.fillMaxSize().pointerInput(ui.isLive) {
                detectTapGestures(
                    onTap = { if (controlsVisible) controlsVisible = false else touch() },
                    onDoubleTap = { offset ->
                        if (!ui.isLive) {
                            val forward = offset.x > size.width / 2
                            actions.seekBy(if (forward) 10_000 else -10_000)
                            seekFlash = if (forward) "+10 s" else "-10 s"
                        }
                    },
                )
            },
        )

        ui.castDevice?.let { device ->
            Column(Modifier.align(Alignment.Center).padding(bottom = 140.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.CastConnected, null, tint = AppColors.Accent, modifier = Modifier.size(52.dp))
                Text("Transmitindo para $device", Modifier.padding(top = 10.dp), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("Use os controles abaixo como controle remoto", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
            }
        }

        if (ui.buffering && ui.error == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(56.dp), color = Color.White)
        }

        seekFlash?.let {
            Text(it, Modifier.align(Alignment.Center).clip(RoundedCornerShape(24.dp)).background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 20.dp, vertical = 10.dp), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }

        AnimatedVisibility(controlsVisible && ui.error == null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                // Topo: voltar + título + ações
                Row(
                    Modifier.fillMaxWidth().align(Alignment.TopCenter)
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = actions::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", tint = Color.White) }
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(ui.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (ui.subtitle.isNotEmpty()) Text(ui.subtitle, color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { actions.toggleResize(); touch() }) { Icon(Icons.Default.AspectRatio, "Ajustar à tela", tint = Color.White) }
                    TvButton(ui, actions, onInteract = ::touch)
                    IconButton(onClick = { actions.download(); touch() }) { Icon(Icons.Default.Download, "Baixar", tint = Color.White) }
                }

                // Centro: anterior / -10 / play-pausa / +10 / próximo
                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (ui.hasPrev || ui.hasNext) RoundButton(Icons.Default.SkipPrevious, "Anterior", ui.hasPrev) { actions.previous(); touch() }
                    if (!ui.isLive) RoundButton(Icons.Default.Replay10, "Voltar 10 s") { actions.seekBy(-10_000); touch() }
                    Box(
                        Modifier.size(72.dp).clip(CircleShape).background(AppColors.Accent.copy(alpha = 0.9f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        IconButton(onClick = { actions.togglePlay(); touch() }, Modifier.size(72.dp)) {
                            Icon(if (ui.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                if (ui.isPlaying) "Pausar" else "Reproduzir", tint = Color.White, modifier = Modifier.size(40.dp))
                        }
                    }
                    if (!ui.isLive) RoundButton(Icons.Default.Forward10, "Avançar 10 s") { actions.seekBy(10_000); touch() }
                    if (ui.hasPrev || ui.hasNext) RoundButton(Icons.Default.SkipNext, "Próximo", ui.hasNext) { actions.next(); touch() }
                }

                // Base: barra de tempo (ou selo "ao vivo")
                Column(
                    Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    if (ui.isLive) {
                        Text("● AO VIVO", color = AppColors.Danger, fontWeight = FontWeight.Bold)
                    } else {
                        val max = ui.duration.coerceAtLeast(1).toFloat()
                        // Enquanto a duração não é conhecida (carregando), a barra fica vazia.
                        val shown = when {
                            dragging -> dragValue
                            ui.duration <= 0 -> 0f
                            else -> ui.position.toFloat().coerceIn(0f, max)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TimeText(shown.toLong())
                            Slider(
                                value = shown,
                                onValueChange = { dragging = true; dragValue = it; touch() },
                                onValueChangeFinished = { actions.seekTo(dragValue.toLong()); dragging = false; touch() },
                                valueRange = 0f..max,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.White, activeTrackColor = AppColors.Accent, inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                                ),
                                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                            )
                            TimeText(ui.duration)
                        }
                    }
                }
            }
        }

        // "Pular abertura": fica visível mesmo com os controles escondidos (como na Netflix)
        AnimatedVisibility(ui.showSkipIntro && ui.error == null, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 92.dp)) {
            OutlinedButton(
                onClick = { actions.skipIntro() },
                border = BorderStroke(1.5.dp, Color.White),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Black.copy(alpha = 0.7f)),
                shape = RoundedCornerShape(8.dp),
            ) {
                Icon(Icons.Default.FastForward, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Pular abertura", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }

        // Fim do episódio: "Próximo episódio em 5…"
        ui.nextUpTitle?.let { nextTitle ->
            Row(
                Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 92.dp).width(380.dp)
                    .clip(RoundedCornerShape(12.dp)).background(AppColors.Surface.copy(alpha = 0.95f)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(120.dp).height(68.dp).clip(RoundedCornerShape(8.dp)).background(AppColors.Surface2)) {
                    ArtImage(ui.nextUpLogo, 160.dp, Modifier.fillMaxSize())
                }
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text("PRÓXIMO EPISÓDIO EM ${ui.nextUpSeconds}", color = AppColors.Muted, fontSize = 11.sp)
                    Text(nextTitle, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    LinearProgressIndicator(
                        progress = { (5 - ui.nextUpSeconds) / 5f },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).height(3.dp).clip(CircleShape),
                        trackColor = Color.White.copy(alpha = 0.2f), drawStopIndicator = {},
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { actions.playNextUp() }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                            Text("Assistir agora", fontSize = 13.sp)
                        }
                        OutlinedButton(onClick = { actions.cancelNextUp() }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                            Text("Cancelar", color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        // "Continuando de 12:34" com opção de começar do início
        AnimatedVisibility(showResume && ui.error == null, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 84.dp)) {
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).background(AppColors.Surface.copy(alpha = 0.92f)).padding(start = 16.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Continuando de ${Format.duration(ui.resumeFrom)}", color = Color.White)
                TextButton(onClick = { actions.startOver(); showResume = false }) { Text("Começar do início") }
            }
        }

        ui.toast?.let {
            Text(it, Modifier.align(Alignment.TopCenter).padding(top = 72.dp).clip(RoundedCornerShape(10.dp))
                .background(AppColors.Surface.copy(alpha = 0.92f)).padding(horizontal = 16.dp, vertical = 10.dp), color = Color.White)
        }

        ui.error?.let { error ->
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.ErrorOutline, null, tint = Color.White, modifier = Modifier.size(48.dp))
                Text(error, Modifier.padding(top = 12.dp), color = Color.White, textAlign = TextAlign.Center)
                Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (ui.certHost != null) Button(onClick = actions::trustServer) { Text("Confiar neste servidor") }
                    else Button(onClick = actions::retry) { Text("Tentar novamente") }
                    OutlinedButton(onClick = actions::back) { Text("Voltar", color = Color.White) }
                }
            }
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)),
    ) {
        Icon(icon, description, tint = if (enabled) Color.White else Color.White.copy(alpha = 0.3f), modifier = Modifier.size(30.dp))
    }
}

/** Botão "TV": transmitir via Google Cast, parar a transmissão ou espelhar a tela do celular. */
@Composable
private fun TvButton(ui: PlayerUiState, actions: PlayerActions, onInteract: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true; onInteract() }) {
            Icon(
                if (ui.castDevice != null) Icons.Default.CastConnected else Icons.Default.Cast,
                "Assistir na TV",
                tint = if (ui.castDevice != null) AppColors.Accent else Color.White,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (ui.castDevice != null) {
                DropdownMenuItem(
                    text = { Text("Parar de transmitir para ${ui.castDevice}") },
                    leadingIcon = { Icon(Icons.Default.CastConnected, null) },
                    onClick = { open = false; actions.stopCasting() },
                )
            } else if (ui.castAvailable) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("Transmitir para a TV")
                            Text("Chromecast, Google TV e TVs com Chromecast", fontSize = 12.sp, color = AppColors.Muted)
                        }
                    },
                    leadingIcon = { Icon(Icons.Default.Cast, null) },
                    onClick = { open = false; actions.castToTv() },
                )
            }
            DropdownMenuItem(
                text = {
                    Column {
                        Text("Espelhar a tela")
                        Text("Smart View, Miracast e outras TVs", fontSize = 12.sp, color = AppColors.Muted)
                    }
                },
                leadingIcon = { Icon(Icons.Default.ScreenShare, null) },
                onClick = { open = false; actions.mirrorScreen() },
            )
        }
    }
}

@Composable
private fun TimeText(ms: Long) {
    Text(Format.duration(ms), color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.width(64.dp),
        textAlign = TextAlign.Center)
}
