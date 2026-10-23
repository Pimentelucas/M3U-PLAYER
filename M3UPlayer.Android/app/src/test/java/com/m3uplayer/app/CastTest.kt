package com.m3uplayer.app

import androidx.media3.common.MimeTypes
import com.m3uplayer.app.cast.CastSupport
import com.m3uplayer.app.model.MediaKind
import com.m3uplayer.app.model.PlaylistEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/** O receptor do Chromecast precisa do tipo do vídeo; a URL nem sempre tem extensão. */
class CastTest {
    private fun entry(url: String, kind: MediaKind = MediaKind.MOVIE) = PlaylistEntry(title = "x", url = url).also { it.kind = kind }

    @Test fun hls() {
        assertEquals(MimeTypes.APPLICATION_M3U8, CastSupport.mimeTypeFor(entry("http://a/b/index.m3u8")))
        assertEquals(MimeTypes.APPLICATION_M3U8, CastSupport.mimeTypeFor(entry("http://a/get.php?id=1&type=m3u8")))
    }

    @Test fun files() {
        assertEquals(MimeTypes.VIDEO_MP4, CastSupport.mimeTypeFor(entry("http://a/filme.mp4?token=1")))
        assertEquals(MimeTypes.VIDEO_MATROSKA, CastSupport.mimeTypeFor(entry("http://a/filme.MKV")))
        assertEquals(MimeTypes.AUDIO_MPEG, CastSupport.mimeTypeFor(entry("http://a/musica.mp3")))
        assertEquals(MimeTypes.VIDEO_MP2T, CastSupport.mimeTypeFor(entry("http://a/canal.ts")))
    }

    @Test fun urlWithoutExtensionUsesKind() {
        assertEquals(MimeTypes.VIDEO_MP2T, CastSupport.mimeTypeFor(entry("http://srv/user/pass/101", MediaKind.LIVE)))
        assertEquals(MimeTypes.VIDEO_MP4, CastSupport.mimeTypeFor(entry("http://srv/AAAAAAAAAAAA1", MediaKind.MOVIE)))
        assertEquals(MimeTypes.VIDEO_MP4, CastSupport.mimeTypeFor(entry("http://srv/AAAAAAAAAAAA2", MediaKind.SERIES)))
    }
}
