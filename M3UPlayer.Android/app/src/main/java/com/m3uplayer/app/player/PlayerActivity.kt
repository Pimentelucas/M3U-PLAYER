package com.m3uplayer.app.player

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import com.google.android.gms.cast.framework.CastContext
import com.m3uplayer.app.M3UApp
import com.m3uplayer.app.cast.CastSupport
import com.m3uplayer.app.data.IntroSkipper
import com.m3uplayer.app.data.Net
import com.m3uplayer.app.data.Tls
import com.m3uplayer.app.download.DownloadManager
import com.m3uplayer.app.model.PlaylistEntry
import com.m3uplayer.app.ui.theme.M3UTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.media3.common.MediaItem as ExoMediaItem

/** Fila de reprodução passada da biblioteca para o player (episódios da série ou itens do grupo). */
object PlaybackSession {
    var queue: List<PlaylistEntry> = emptyList()
    var index: Int = 0
}

/**
 * Player em tela cheia (ExoPlayer/Media3). Salva a posição a cada 5 s no histórico,
 * retoma de onde parou, passa para o próximo episódio sozinho e tenta HLS quando
 * uma URL sem extensão não é reconhecida.
 *
 * Transmissão para TV: quando uma sessão do Google Cast é aberta, o vídeo passa para a TV
 * (que busca o stream direto do servidor) na mesma posição, e os controles da tela passam a
 * comandar a TV. Ao desconectar, o vídeo volta para o celular de onde a TV parou.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity(), PlayerActions {
    private val app get() = application as M3UApp
    private lateinit var player: ExoPlayer
    private var castContext: CastContext? = null
    private var castPlayer: CastPlayer? = null

    /** O player que está tocando agora: o do celular ou o da TV. */
    private lateinit var active: Player
    private val casting get() = castPlayer != null && active === castPlayer

    private val ui = PlayerUiState()
    private var queue: List<PlaylistEntry> = emptyList()
    private var index = 0
    private var triedAsHls = false
    private var lastPositionSave = 0L
    private var lastFlush = 0L

    private val current: PlaylistEntry? get() = queue.getOrNull(index)

    private val intro by lazy { IntroSkipper(app.settings.introEnds) { app.settings.saveIntroEnds() } }
    private var nextUpJob: Job? = null
    private var nextUpIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterImmersive()

        queue = PlaybackSession.queue.ifEmpty {
            // Processo recriado pelo sistema: monta o item a partir do Intent.
            listOf(PlaylistEntry(title = intent.getStringExtra(EXTRA_TITLE).orEmpty(), url = intent.getStringExtra(EXTRA_URL).orEmpty()))
        }
        index = PlaybackSession.index.coerceIn(0, queue.lastIndex)

        // Se o decodificador principal do aparelho falhar, tenta os alternativos (comum em TV boxes baratas).
        val renderers = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        player = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(localListener)
        active = player

        castContext = CastSupport.context(this)
        castPlayer = castContext?.let { cast ->
            CastPlayer(cast).apply {
                addListener(castListener)
                setSessionAvailabilityListener(sessionListener)
            }
        }
        ui.castAvailable = castContext != null

        // Já havia uma TV conectada: o vídeo vai direto para ela (já na posição de "continuar").
        castPlayer?.takeIf { it.isCastSessionAvailable }?.let { cast ->
            active = cast
            ui.castDevice = castContext?.let { CastSupport.deviceName(it) } ?: "TV"
        }
        play(index, fromStart = intent.getBooleanExtra(EXTRA_FROM_START, false))

        setContent { M3UTheme { PlayerScreen(player, ui, this) } }

        // Atualiza tempo/barra e salva a posição periodicamente.
        lifecycleScope.launch {
            while (true) {
                tick()
                delay(500)
            }
        }
    }

    private fun enterImmersive() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ───────────────────────── Reproduzir ─────────────────────────

    private fun play(newIndex: Int, fromStart: Boolean = false) {
        savePosition()
        clearNextUp()
        ui.showSkipIntro = false
        index = newIndex
        val entry = current ?: return finish()
        triedAsHls = false

        val record = app.history.get(entry.url)
        val resumeMs = if (!fromStart && record?.canResume == true) record.positionMs else 0L

        ui.title = if (entry.isEpisode) entry.seriesName.orEmpty() else entry.title
        ui.subtitle = if (entry.isEpisode) "${entry.episodeCode} · ${entry.episodeHeading}" else entry.displayGroup
        ui.hasPrev = index > 0
        ui.hasNext = index < queue.lastIndex
        ui.error = null
        ui.position = resumeMs
        ui.duration = 0
        ui.buffering = true
        ui.resumeFrom = resumeMs

        if (casting) {
            castPlayer?.let { loadOnCast(it, entry, resumeMs) }
        } else {
            prepare(entry, resumeMs, forceHls = false)
        }

        app.history.touch(entry)
        if (fromStart) app.history.restart(entry.url)
        app.history.save()
    }

    private fun prepare(entry: PlaylistEntry, startMs: Long, forceHls: Boolean) {
        val http = OkHttpDataSource.Factory(app.callFactory)
            .setUserAgent(entry.userAgent?.takeIf { it.isNotBlank() } ?: Net.USER_AGENT)
            .apply { entry.referrer?.takeIf { it.isNotBlank() }?.let { setDefaultRequestProperties(mapOf("Referer" to it)) } }
        // DefaultDataSource: http(s) pelo OkHttp; content:// e arquivos locais também funcionam.
        val dataSource = DefaultDataSource.Factory(this, http)
        val item = ExoMediaItem.Builder().setUri(entry.url).apply {
            if (forceHls || DownloadManager.looksLikeHls(entry.url)) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        player.setMediaSource(DefaultMediaSourceFactory(dataSource).createMediaSource(item), startMs)
        player.prepare()
        player.playWhenReady = true
    }

    private fun loadOnCast(cast: CastPlayer, entry: PlaylistEntry, startMs: Long) {
        cast.setMediaItem(CastSupport.mediaItemFor(entry), startMs)
        cast.prepare()
        cast.playWhenReady = true
    }

    // ───────────────────────── Celular ⇄ TV ─────────────────────────

    private val sessionListener = object : SessionAvailabilityListener {
        override fun onCastSessionAvailable() = switchToCast()
        override fun onCastSessionUnavailable() = switchToLocal()
    }

    private fun switchToCast() {
        val cast = castPlayer ?: return
        val entry = current ?: return
        val position = if (isVod(player)) player.currentPosition else 0L
        player.pause()
        active = cast
        ui.castDevice = castContext?.let { CastSupport.deviceName(it) } ?: "TV"
        ui.error = null
        ui.buffering = true
        loadOnCast(cast, entry, position)
        ui.showToast("Transmitindo para ${ui.castDevice}")
    }

    private fun switchToLocal() {
        val cast = castPlayer ?: return
        if (active !== cast) return
        val position = if (isVod(cast)) cast.currentPosition else 0L
        active = player
        ui.castDevice = null
        ui.error = null
        ui.buffering = true
        // O item pode ter mudado enquanto estava na TV: carrega o atual no celular, na posição da TV.
        current?.let { prepare(it, position, forceHls = triedAsHls) }
        ui.showToast("A transmissão terminou — continuando no celular")
    }

    // ───────────────────────── Eventos dos players ─────────────────────────

    private val localListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (casting) return
            ui.isPlaying = isPlaying
            if (!isPlaying) savePosition()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (casting) return
            ui.buffering = state == Player.STATE_BUFFERING
            if (state == Player.STATE_ENDED) onEnded()
        }

        override fun onPlayerError(error: PlaybackException) {
            if (casting) return
            val entry = current ?: return
            val unrecognized = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
            if (unrecognized && !triedAsHls && !DownloadManager.looksLikeHls(entry.url)) {
                // URL sem extensão que na verdade é HLS: tenta de novo como .m3u8
                triedAsHls = true
                prepare(entry, 0, forceHls = true)
                return
            }
            ui.buffering = false
            if (Tls.isCertificateProblem(error)) {
                ui.certHost = Tls.hostOf(entry.url)
                ui.error = "O certificado de segurança (HTTPS) do servidor deste vídeo não é reconhecido pelo Android."
                return
            }
            ui.error = when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "O servidor recusou o vídeo (erro HTTP). O canal pode estar fora do ar."
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Sem conexão com o servidor do vídeo."
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "O aparelho não suporta este formato de vídeo (ex.: H.265 em alta resolução)."
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "Formato de vídeo não reconhecido."
                else -> "Não foi possível reproduzir (${error.errorCodeName})."
            }
        }
    }

    private val castListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!casting) return
            ui.isPlaying = isPlaying
            if (!isPlaying) savePosition()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (!casting) return
            ui.buffering = state == Player.STATE_BUFFERING
            if (state == Player.STATE_ENDED) onEnded()
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!casting) return
            ui.buffering = false
            ui.error = "A TV não conseguiu reproduzir este vídeo. O Chromecast não toca alguns formatos " +
                "(como canais .ts ao vivo) nem servidores que exigem configurações especiais. Tente \"Espelhar a tela\"."
        }
    }

    // ───────────────────────── Tempo e histórico ─────────────────────────

    private fun isVod(p: Player): Boolean =
        !p.isCurrentMediaItemLive && p.duration != C.TIME_UNSET && p.duration > 0 && p.isCurrentMediaItemSeekable

    private fun tick() {
        val p = active
        val vod = isVod(p)
        // Só dá para saber se é ao vivo depois que o stream abriu.
        if (p.playbackState == Player.STATE_READY) {
            ui.isLive = !vod
            ui.buffering = false
        }
        if (vod) {
            ui.duration = p.duration
            ui.position = p.currentPosition
        }
        ui.showSkipIntro = vod && ui.nextUpTitle == null && intro.shouldShow(current, p.currentPosition, p.duration)

        // Salva a posição a cada 5 s (em memória) e grava o arquivo a cada 20 s.
        val now = System.currentTimeMillis()
        val entry = current ?: return
        if (!vod || !p.isPlaying || now - lastPositionSave < 5000) return
        lastPositionSave = now
        app.history.updatePosition(entry.url, p.currentPosition, p.duration)
        if (now - lastFlush > 20_000) {
            lastFlush = now
            app.history.save()
        }
    }

    private fun savePosition() {
        val entry = current ?: return
        if (::active.isInitialized && isVod(active) && active.currentPosition > 0) {
            app.history.updatePosition(entry.url, active.currentPosition, active.duration)
        }
        app.history.save()
    }

    private fun onEnded() {
        val entry = current ?: return
        val vod = active.duration != C.TIME_UNSET && active.duration > 0
        if (vod) {
            app.history.markCompleted(entry.url)
            app.history.save()
        }
        val next = entry.series?.nextAfter(entry)
        if (next != null && index + 1 < queue.size && queue[index + 1] === next) {
            showNextUp(index + 1)
            return
        }
        if (!vod) {
            ui.error = "A transmissão foi interrompida."
            return
        }
        if (!casting) finish()
    }

    // ───────────────────────── Ações da tela ─────────────────────────

    override fun back() {
        savePosition()
        finish()
    }

    override fun togglePlay() {
        if (active.playbackState == Player.STATE_ENDED) active.seekTo(0)
        if (active.isPlaying) active.pause() else active.play()
    }

    override fun seekBy(deltaMs: Long) {
        if (!isVod(active)) return
        seekTo((active.currentPosition + deltaMs).coerceIn(0, active.duration - 1000))
    }

    override fun seekTo(positionMs: Long) {
        val from = active.currentPosition
        active.seekTo(positionMs)
        ui.position = positionMs
        learnFromSeek(from, positionMs)
    }

    // ───────────────────────── Pular abertura / próximo episódio ─────────────────────────

    override fun skipIntro() {
        val entry = current ?: return
        val target = intro.skip(entry, System.currentTimeMillis())
        active.seekTo(target)
        ui.position = target
        ui.showSkipIntro = false
        if (!intro.isLearned(entry))
            ui.showToast("Pulou para 1:30. Se a abertura terminar em outro ponto, ajuste a barra: o app aprende.")
    }

    /** Mudança manual de posição: pode ensinar onde a abertura desta série termina. */
    private fun learnFromSeek(fromMs: Long, toMs: Long) {
        val learned = intro.onManualSeek(current, fromMs, toMs, System.currentTimeMillis()) ?: return
        ui.showToast("Abertura de ${current?.seriesName} termina em ${com.m3uplayer.app.util.Format.duration(learned)} — vale para os próximos episódios.")
    }

    private fun showNextUp(nextIndex: Int) {
        val next = queue.getOrNull(nextIndex) ?: return
        nextUpIndex = nextIndex
        ui.nextUpTitle = "${next.episodeCode} · ${next.episodeHeading}"
        ui.nextUpLogo = next.logo
        ui.showSkipIntro = false
        nextUpJob?.cancel()
        nextUpJob = lifecycleScope.launch {
            for (seconds in NEXT_UP_SECONDS downTo 1) {
                ui.nextUpSeconds = seconds
                delay(1000)
            }
            playNextUp()
        }
    }

    override fun playNextUp() {
        val target = nextUpIndex
        clearNextUp()
        if (target in queue.indices) play(target)
    }

    override fun cancelNextUp() = clearNextUp()

    private fun clearNextUp() {
        nextUpJob?.cancel()
        nextUpJob = null
        nextUpIndex = -1
        ui.nextUpTitle = null
        ui.nextUpLogo = null
    }

    override fun previous() { if (index > 0) play(index - 1) }

    override fun next() { if (index < queue.lastIndex) play(index + 1) }

    override fun startOver() {
        active.seekTo(0)
        current?.let { app.history.restart(it.url) }
    }

    override fun download() {
        val entry = current ?: return
        app.downloads.enqueue(entry)
        ui.showToast("Baixando: ${entry.title}")
    }

    override fun trustServer() {
        ui.certHost?.let { app.settings.trustHost(it) }
        ui.certHost = null
        retry()
    }

    override fun retry() {
        val entry = current ?: return
        ui.error = null
        ui.certHost = null
        ui.buffering = true
        val position = if (isVod(active)) active.currentPosition else 0
        val cast = castPlayer
        if (casting && cast != null) loadOnCast(cast, entry, position)
        else prepare(entry, position, forceHls = triedAsHls)
    }

    override fun castToTv() {
        val cast = castContext
        if (cast == null) {
            ui.showToast("Este aparelho não tem o Google Play Services; use \"Espelhar a tela\".")
            return
        }
        CastSupport.showDevicePicker(this, cast)
    }

    override fun stopCasting() {
        castContext?.let { CastSupport.stopCasting(it) }
    }

    override fun mirrorScreen() {
        if (!CastSupport.openScreenMirroring(this)) {
            ui.showToast("Abra o espelhamento de tela pelo painel de atalhos do celular (Smart View / Transmitir tela).")
        }
    }

    override fun toggleResize() {
        ui.resizeMode = when (ui.resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        ui.showToast(when (ui.resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Preencher (cortando as bordas)"
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Esticar"
            else -> "Ajustar à tela"
        })
    }

    // ───────────────────────── Ciclo de vida ─────────────────────────

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    override fun onStop() {
        super.onStop()
        // Na TV o vídeo continua mesmo com o celular bloqueado; no celular, pausa.
        if (!casting) player.pause()
        savePosition()
    }

    override fun onDestroy() {
        savePosition()
        player.removeListener(localListener)
        player.release()
        castPlayer?.run {
            setSessionAvailabilityListener(null)
            removeListener(castListener)
            release()
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_FROM_START = "fromStart"
        private const val NEXT_UP_SECONDS = 5

        fun start(context: Context, entry: PlaylistEntry, queue: List<PlaylistEntry>, fromStart: Boolean = false) {
            val list = queue.ifEmpty { listOf(entry) }
            PlaybackSession.queue = list
            PlaybackSession.index = list.indexOfFirst { it === entry }.takeIf { it >= 0 } ?: 0
            if (list.getOrNull(PlaybackSession.index) !== entry) {
                PlaybackSession.queue = listOf(entry)
                PlaybackSession.index = 0
            }
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra(EXTRA_URL, entry.url)
                    .putExtra(EXTRA_TITLE, entry.title)
                    .putExtra(EXTRA_FROM_START, fromStart)
            )
        }
    }
}
