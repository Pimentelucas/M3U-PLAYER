package com.m3uplayer.app

import com.m3uplayer.app.data.Artwork
import com.m3uplayer.app.data.IntroSkipper
import com.m3uplayer.app.download.DownloadManager
import com.m3uplayer.app.download.DownloadScanner
import com.m3uplayer.app.download.FolderSink
import com.m3uplayer.app.model.SeriesInfo
import com.m3uplayer.app.data.M3UParser
import com.m3uplayer.app.data.MediaDescriber
import com.m3uplayer.app.data.MediaLibrary
import com.m3uplayer.app.data.WatchRecord
import com.m3uplayer.app.download.DownloadGroup
import com.m3uplayer.app.download.DownloadInfo
import com.m3uplayer.app.download.DownloadState
import com.m3uplayer.app.model.MediaKind
import com.m3uplayer.app.model.PlaylistEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Pular abertura, texto da prévia e downloads agrupados (mesmas regras da versão Windows). */
class FeaturesTest {
    private val lib = MediaLibrary.build(M3UParser.parse("""
        #EXTM3U
        #EXTINF:-1 group-title="♦️ Ação ✔️",Missão Impossível (2023) [L] 4K
        http://srv/AAAAAAAAAAAAAAAA1.mp4
        #EXTINF:-1 tvg-logo="http://l/show.jpg" group-title="Netflix",Meu Show S01E01
        http://srv/AAAAAAAAAAAAAAAA4
        #EXTINF:-1 group-title="Netflix",Meu Show S01E02
        http://srv/AAAAAAAAAAAAAAAA5
        #EXTINF:-1 group-title="Netflix",Meu Show S02E01
        http://srv/AAAAAAAAAAAAAAAA6
    """.trimIndent(), null))
    private val movie = lib.entries[0]
    private val show = lib.findSeries("Netflix", "Meu Show")!!
    private val ep1 = show.episodes[0]

    @Test fun introButtonWindow() {
        val intro = IntroSkipper(mutableMapOf())
        assertFalse(intro.shouldShow(ep1, 1_000, 40 * 60_000))
        assertTrue(intro.shouldShow(ep1, 10_000, 40 * 60_000))
        assertFalse(intro.shouldShow(ep1, 88_000, 40 * 60_000))
        assertFalse(intro.shouldShow(movie, 10_000, 120 * 60_000))
        assertFalse(intro.shouldShow(ep1, 10_000, 2 * 60_000))
    }

    @Test fun introLearnsFromAdjustment() {
        val learned = mutableMapOf<String, Long>()
        var saved = 0
        val intro = IntroSkipper(learned) { saved++ }
        assertEquals(90_000L, intro.skip(ep1, 1_000))
        assertEquals(85_000L, intro.onManualSeek(ep1, 95_000, 85_000, 10_000))
        assertEquals(1, saved)
        assertEquals(85_000L, intro.skip(show.episodes[1], 100_000))
    }

    @Test fun introLearnsFromManualDragOnlyAtTheStart() {
        val intro = IntroSkipper(mutableMapOf())
        assertEquals(72_000L, intro.onManualSeek(ep1, 5_000, 72_000, 0))
        assertNull(IntroSkipper(mutableMapOf()).onManualSeek(ep1, 30 * 60_000, 31 * 60_000, 999_999))
    }

    @Test fun previewDescriptions() {
        val d = MediaDescriber.describe(movie, null)
        assertEquals("2023  ·  Filme  ·  4K  ·  Legendado", d.meta)
        assertEquals("Filme de Ação, de 2023.", d.text)

        val resume = WatchRecord(url = movie.url, positionMs = 600_000, durationMs = 7_200_000)
        val r = MediaDescriber.describe(movie, resume)
        assertEquals("Continuar", r.playLabel)
        assertTrue(r.text.contains("Você parou em 10:00 de 2:00:00"))

        val s = MediaDescriber.describe(show, null, ep1)
        assertTrue(s.text.startsWith("Série com 2 temporadas e 3 episódios"))
        assertEquals("Assistir T1 E1", s.playLabel)

        val channel = PlaylistEntry(title = "RECORD SP FHD", group = "♦️ CANAIS | FHD H265 ⭐").also { it.kind = MediaKind.LIVE }
        assertEquals("Canal ao vivo em Full HD, do grupo Canais · FHD H265.", MediaDescriber.describe(channel, null).text)
        assertEquals("Rede Record", MediaDescriber.cleanGroup("♦️  REDE RECORD"))
    }

    @Test fun downloadsGroupedBySeries() {
        fun info(id: Long, e: PlaylistEntry, state: DownloadState = DownloadState.COMPLETED) = DownloadInfo(
            id, e.title, state = state, logo = e.series?.logo ?: e.logo, group = e.group, seriesName = e.seriesName,
            season = e.season, episode = e.episode, episodeLabel = if (e.isEpisode) e.episodeCode else null, square = e.isSquare,
        )
        val groups = DownloadGroup.build(listOf(info(1, show.episodes[2]), info(2, movie), info(3, ep1, DownloadState.RUNNING)))
        assertEquals(2, groups.size)
        assertTrue(groups[0].isSeries)
        assertEquals("Meu Show", groups[0].title)
        assertEquals("http://l/show.jpg", groups[0].logo)
        assertEquals(listOf("T1 E1", "T2 E1"), groups[0].items.map { it.episodeLabel })
        assertEquals("2 episódios  ·  1 baixando  ·  1 concluído", groups[0].summary)
        assertFalse(groups[1].isSeries)
    }

    @Test fun tmdbImagesAreResizedForTheCard() {
        assertEquals("https://image.tmdb.org/t/p/w500/abc.jpg", Artwork.optimize("https://image.tmdb.org/t/p/w1280/abc.jpg", 374))
        assertEquals("https://image.tmdb.org/t/p/w342/abc.jpg", Artwork.optimize("https://image.tmdb.org/t/p/w600_and_h900_bestv2/abc.jpg", 300))
        assertEquals("http://outro/x.jpg", Artwork.optimize("http://outro/x.jpg", 374))
    }

    @Test fun seriesUsesTheListImageOfTheFirstEpisode() {
        val library = MediaLibrary.build(M3UParser.parse("""
            #EXTM3U
            #EXTINF:-1 tvg-logo="https://image.tmdb.org/t/p/w1280/cena1.jpg" group-title="S",Show S01E01
            http://srv/e1
            #EXTINF:-1 tvg-logo="https://image.tmdb.org/t/p/w600_and_h900_bestv2/poster.jpg" group-title="S",Show S01E02
            http://srv/e2
        """.trimIndent(), null))
        val series = library.groups.flatMap { it.items }.filterIsInstance<SeriesInfo>().single()
        assertTrue(series.logo!!.contains("cena1.jpg"))
    }

    @Test fun episodesGoToASeriesSubfolder() {
        assertEquals("Meu Show", DownloadManager.seriesFolder(ep1))
        assertNull(DownloadManager.seriesFolder(movie))
        val root = Files.createTempDirectory("m3u-sink").toFile()
        try {
            val file = FolderSink(root).create("Meu Show S01E01.mp4", "video/mp4", "Meu Show")
            assertTrue(File(root, "Meu Show/Meu Show S01E01.mp4").exists())
            assertEquals("Meu Show S01E01.mp4", file.displayName)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun filesFromEarlierDownloadsAreRecognized() {
        assertEquals("Dexter S02E03", DownloadScanner.titleOf("Dexter S02E03 (2).mp4"))
        assertEquals("Filme (2008)", DownloadScanner.titleOf("Filme (2008).mp4"))
        val fromList = DownloadScanner.infoFor(DownloadScanner.FoundFile("Meu Show S01E02.mp4", "content://x/1", 10, 1), lib)
        assertEquals("http://l/show.jpg", fromList.logo)
        assertEquals("Meu Show", fromList.seriesName)
        assertTrue(fromList.fromDisk && fromList.state == DownloadState.COMPLETED)
        val unknown = DownloadScanner.infoFor(DownloadScanner.FoundFile("Dexter S02E03 (2).mp4", "content://x/2", 10, 2), lib)
        assertEquals("Dexter", unknown.seriesName)
        assertEquals(2, unknown.season)
    }
}
