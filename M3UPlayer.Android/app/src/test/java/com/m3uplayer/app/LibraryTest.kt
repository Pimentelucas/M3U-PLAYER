package com.m3uplayer.app

import com.m3uplayer.app.data.M3UParser
import com.m3uplayer.app.data.MediaLibrary
import com.m3uplayer.app.data.WatchHistory
import com.m3uplayer.app.model.MediaKind
import com.m3uplayer.app.model.SeriesInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI
import java.nio.file.Files

class ParserTest {
    private val m3u = """
        #EXTM3U x-tvg-url="http://epg"
        #EXTINF:-1 tvg-id="globo.br" tvg-logo="http://logo/g.png" group-title="Abertos, HD",Globo SP, HD
        #EXTVLCOPT:http-user-agent=MyAgent/1.0
        #EXTVLCOPT:http-referrer=http://ref/
        http://host/live/1.ts
        #EXTINF:123.5,Artista - Música
        musicas/faixa%2001.mp3
        #EXTGRP:Filmes
        #EXTINF:-1,Filme X
        http://host/movie/x.mp4

        http://host/semtitulo/video.mkv
    """.trimIndent()

    private val entries = M3UParser.parse(m3u, URI("http://srv/listas/minha.m3u"))

    @Test fun countsItems() = assertEquals(4, entries.size)
    @Test fun titleWithComma() = assertEquals("Globo SP, HD", entries[0].title)
    @Test fun groupWithCommaInQuotes() = assertEquals("Abertos, HD", entries[0].group)
    @Test fun logoAndTvgId() {
        assertEquals("http://logo/g.png", entries[0].logo)
        assertEquals("globo.br", entries[0].tvgId)
    }
    @Test fun vlcOptions() {
        assertEquals("MyAgent/1.0", entries[0].userAgent)
        assertEquals("http://ref/", entries[0].referrer)
    }
    @Test fun durationAndRelativeUrl() {
        assertEquals(123.5, entries[1].duration, 0.001)
        assertEquals("http://srv/listas/musicas/faixa%2001.mp3", entries[1].url)
    }
    @Test fun extGrp() = assertEquals("Filmes", entries[2].group)
    @Test fun itemWithoutExtInf() {
        assertEquals("video", entries[3].title)
        assertEquals("", entries[3].group)
    }
    @Test fun detectsHls() = assertTrue(M3UParser.isHlsStream("#EXTM3U\n#EXT-X-TARGETDURATION:6\nseg0.ts"))
}

class MediaLibraryTest {
    private val text = """
        #EXTM3U
        #EXTINF:-1 tvg-logo="http://l/rec.png" group-title="CANAIS",RECORD SP HD
        http://srv/user/pass/101
        #EXTINF:-1 group-title="CANAIS",GLOBO RJ
        http://srv/user/pass/102
        #EXTINF:-1 group-title="Ação",Missão Impossível (2023)
        http://srv/AAAAAAAAAAAAAAAA1
        #EXTINF:-1 group-title="Ação",Velozes 10 (2023)
        http://srv/AAAAAAAAAAAAAAAA2
        #EXTINF:-1 group-title="Legendados",Filme Sem Ano [L]
        http://srv/AAAAAAAAAAAAAAAA3
        #EXTINF:-1 tvg-logo="http://l/show.jpg" group-title="Netflix",Meu Show S01E02
        http://srv/AAAAAAAAAAAAAAAA5
        #EXTINF:-1 group-title="Netflix",Meu Show S01E01
        http://srv/AAAAAAAAAAAAAAAA4
        #EXTINF:-1 group-title="Netflix",Meu Show S02E01 - A Volta
        http://srv/AAAAAAAAAAAAAAAA6
        #EXTINF:-1 group-title="Netflix",Outra Série (2026) S01E01
        http://srv/AAAAAAAAAAAAAAAA7
    """.trimIndent()
    private val lib = MediaLibrary.build(M3UParser.parse(text, null))
    private val byTitle = lib.entries.associateBy { it.title }
    private val show = lib.findSeries("Netflix", "Meu Show")!!

    @Test fun liveChannels() {
        assertEquals(MediaKind.LIVE, byTitle.getValue("RECORD SP HD").kind)
        assertEquals(MediaKind.LIVE, byTitle.getValue("GLOBO RJ").kind)
    }
    @Test fun movieByYear() = assertEquals(MediaKind.MOVIE, byTitle.getValue("Missão Impossível (2023)").kind)
    @Test fun movieByUrlShape() = assertEquals(MediaKind.MOVIE, byTitle.getValue("Filme Sem Ano [L]").kind)
    @Test fun episodesGroupedIntoSeries() {
        val netflix = lib.findGroup("Netflix")!!
        assertEquals(MediaKind.SERIES, netflix.kind)
        assertEquals(2, netflix.items.size)
        assertTrue(netflix.items.any { it is SeriesInfo && it.title == "Outra Série (2026)" })
    }
    @Test fun episodesSorted() {
        assertEquals(listOf("T1 E1", "T1 E2", "T2 E1"), show.episodes.map { it.episodeCode })
        assertEquals(listOf(1, 2), show.seasons)
    }
    @Test fun episodeTitleAndPoster() {
        assertEquals("A Volta", show.episodes[2].episodeTitle)
        assertEquals("http://l/show.jpg", show.logo)
    }
    @Test fun groupsInListOrder() = assertEquals(listOf("CANAIS", "Ação", "Legendados", "Netflix"), lib.groups.map { it.name })
    @Test fun searchByTitleAndKind() {
        assertEquals(1, lib.search("show", null, 10).size)
        assertEquals(1, lib.search("velozes", MediaKind.MOVIE, 10).size)
        assertEquals(0, lib.search("velozes", MediaKind.LIVE, 10).size)
    }
    @Test fun nextEpisode() {
        assertEquals(show.episodes[1], show.nextAfter(show.episodes[0]))
        assertNull(show.nextAfter(show.episodes[2]))
    }
}

class WatchHistoryTest {
    private val dir: File = Files.createTempDirectory("hist").toFile()
    private val lib = MediaLibrary.build(M3UParser.parse(
        "#EXTM3U\n#EXTINF:-1,Filme (2020)\nhttp://x/f.mp4\n#EXTINF:-1,Canal\nhttp://x/live/1\n#EXTINF:-1 group-title=\"S\",Show S01E01\nhttp://x/s1.mp4\n", null))
    private val movie = lib.entries[0]

    @Test fun savesPositionAndResumes() {
        val history = WatchHistory(File(dir, "h.json"))
        history.touch(movie)
        history.updatePosition(movie.url, 30 * 60_000, 120 * 60_000)
        val record = history.get(movie.url)!!
        assertTrue(record.canResume)
        assertEquals(0.25f, record.progress, 0.001f)
    }

    @Test fun mostRecentFirstAndPersisted() {
        val file = File(dir, "p.json")
        val history = WatchHistory(file)
        history.touch(movie)
        history.updatePosition(movie.url, 1_800_000, 7_200_000)
        history.touch(lib.entries[1])
        history.save()
        val reloaded = WatchHistory(file)
        assertEquals(lib.entries[1].url, reloaded.items.value[0].url)
        assertEquals(1_800_000, reloaded.get(movie.url)!!.positionMs)
        assertEquals(MediaKind.MOVIE, reloaded.get(movie.url)!!.kind)
    }

    @Test fun nearTheEndCountsAsWatched() {
        val history = WatchHistory(null)
        history.touch(movie)
        history.updatePosition(movie.url, 118 * 60_000, 120 * 60_000)
        val record = history.get(movie.url)!!
        assertTrue(record.completed)
        assertEquals(0, record.positionMs)
        assertFalse(record.canResume)
    }

    @Test fun finishedRule() {
        assertFalse(WatchHistory.isFinished(60_000, 120 * 60_000))
        assertTrue(WatchHistory.isFinished(112 * 60_000, 120 * 60_000))
    }

    @Test fun lessThanTenSecondsDoesNotOfferResume() {
        val history = WatchHistory(null)
        val episode = lib.entries[2]
        history.touch(episode)
        history.updatePosition(episode.url, 5_000, 40 * 60_000)
        val record = history.get(episode.url)!!
        assertFalse(record.canResume)
        assertEquals("Show", record.seriesName)
        assertNotNull(record.toEntry())
    }
}
