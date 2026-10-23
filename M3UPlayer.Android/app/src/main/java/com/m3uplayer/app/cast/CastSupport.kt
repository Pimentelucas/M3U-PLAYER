package com.m3uplayer.app.cast

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.ContextThemeWrapper
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.mediarouter.app.MediaRouteChooserDialog
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.m3uplayer.app.download.DownloadManager
import com.m3uplayer.app.model.PlaylistEntry

/**
 * Configuração do Google Cast. Usa o "Default Media Receiver" do Google, que não precisa de cadastro:
 * a TV recebe a URL e busca o vídeo direto do servidor.
 */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        .setStopReceiverApplicationWhenEndingSession(true)
        .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

object CastSupport {
    /** null quando o aparelho não tem o Google Play Services (o botão de transmitir some). */
    @Suppress("DEPRECATION")
    fun context(context: Context): CastContext? = runCatching { CastContext.getSharedInstance(context) }.getOrNull()

    /** Lista de TVs/Chromecasts na mesma rede Wi-Fi. */
    fun showDevicePicker(activity: Activity, cast: CastContext) {
        val selector = cast.mergedSelector ?: return
        // O seletor do MediaRouter exige um tema AppCompat.
        val themed = ContextThemeWrapper(activity, androidx.appcompat.R.style.Theme_AppCompat_NoActionBar)
        MediaRouteChooserDialog(themed).apply {
            routeSelector = selector
            show()
        }
    }

    fun stopCasting(cast: CastContext) = cast.sessionManager.endCurrentSession(true)

    fun deviceName(cast: CastContext): String? = cast.sessionManager.currentCastSession?.castDevice?.friendlyName

    /**
     * Abre o espelhamento de tela do Android (Smart View, Miracast, "Transmitir tela"...), para TVs sem Chromecast.
     * @return false se o aparelho não tem essa tela de configurações.
     */
    fun openScreenMirroring(activity: Activity): Boolean {
        for (action in listOf(Settings.ACTION_CAST_SETTINGS, "android.settings.WIFI_DISPLAY_SETTINGS")) {
            try {
                activity.startActivity(Intent(action))
                return true
            } catch (_: ActivityNotFoundException) {
                // tenta a próxima
            } catch (_: SecurityException) {
                // tenta a próxima
            }
        }
        return false
    }

    /** O receptor do Cast precisa saber o tipo do vídeo. */
    fun mimeTypeFor(entry: PlaylistEntry): String {
        if (DownloadManager.looksLikeHls(entry.url)) return MimeTypes.APPLICATION_M3U8
        val ext = entry.url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4", "m4v" -> MimeTypes.VIDEO_MP4
            "webm" -> MimeTypes.VIDEO_WEBM
            "mkv" -> MimeTypes.VIDEO_MATROSKA
            "mp3" -> MimeTypes.AUDIO_MPEG
            "aac" -> MimeTypes.AUDIO_AAC
            "m4a" -> MimeTypes.AUDIO_MP4
            "ts" -> MimeTypes.VIDEO_MP2T
            // URLs sem extensão: filmes/séries de IPTV costumam ser MP4; canais ao vivo, MPEG-TS.
            else -> if (entry.kind == com.m3uplayer.app.model.MediaKind.LIVE) MimeTypes.VIDEO_MP2T else MimeTypes.VIDEO_MP4
        }
    }

    fun mediaItemFor(entry: PlaylistEntry): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(if (entry.isEpisode) "${entry.seriesName} · ${entry.episodeCode}" else entry.title)
            .setSubtitle(entry.displayGroup)
            .apply { entry.logo?.takeIf { it.startsWith("http") }?.let { setArtworkUri(it.toUri()) } }
            .build()
        return MediaItem.Builder()
            .setUri(entry.url)
            .setMimeType(mimeTypeFor(entry))
            .setMediaMetadata(metadata)
            .build()
    }
}
