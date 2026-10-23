package com.m3uplayer.app

import com.m3uplayer.app.data.InvalidPlaylistException
import com.m3uplayer.app.data.PlaylistLoader
import com.m3uplayer.app.data.PlaylistOrigin
import com.m3uplayer.app.download.DownloadInfo
import com.m3uplayer.app.download.DownloadManager
import com.m3uplayer.app.download.DownloadState
import com.m3uplayer.app.download.FolderSink
import com.m3uplayer.app.model.PlaylistEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Downloads e carregamento de listas contra um servidor HTTP local. */
class NetworkTest {
    private val server = MockWebServer()
    private val routes = HashMap<String, Pair<String, ByteArray>>()
    private val random = Random(42)
    private val file = bytes(3_000_000)
    private val segments = List(12) { bytes(50_000 + random.nextInt(10_000)) }
    private val key = bytes(16)
    private val dir: File = Files.createTempDirectory("m3u").toFile()
    private val http = OkHttpClient()
    private var lastUserAgent: String? = null

    private fun bytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }
    private fun url(path: String) = server.url(path).toString()

    private fun encrypt(data: ByteArray, sequence: Long): ByteArray {
        val iv = ByteArray(16).also { for (i in 0 until 8) it[15 - i] = (sequence ushr (8 * i)).toByte() }
        return Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(data)
        }
    }

    @Before
    fun setUp() {
        routes["/files/clip.mp4"] = "video/mp4" to file
        routes["/live/canal"] = "video/mp2t" to file
        val plain = StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n")
        val enc = StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:100\n#EXT-X-KEY:METHOD=AES-128,URI=\"../key.bin\"\n")
        segments.forEachIndexed { i, data ->
            routes["/hls/seg$i.ts"] = "video/mp2t" to data
            routes["/enc/v/s$i.ts"] = "video/mp2t" to encrypt(data, 100L + i)
            plain.append("#EXTINF:4.0,\nseg$i.ts\n")
            enc.append("#EXTINF:4.0,\ns$i.ts\n")
        }
        plain.append("#EXT-X-ENDLIST\n")
        enc.append("#EXT-X-ENDLIST\n")
        routes["/hls/index.m3u8"] = "application/vnd.apple.mpegurl" to plain.toString().toByteArray()
        routes["/enc/v/index.m3u8"] = "application/vnd.apple.mpegurl" to enc.toString().toByteArray()
        routes["/enc/key.bin"] = "application/octet-stream" to key
        routes["/master.m3u8"] = "application/x-mpegurl" to
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=500000\nnope/low.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2000000\nhls/index.m3u8\n".toByteArray()
        routes["/stream?id=7"] = "application/x-mpegURL" to plain.toString().replace("seg", "hls/seg").toByteArray()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.startsWith("/files")) lastUserAgent = request.getHeader("User-Agent")
                val (type, body) = routes[request.path] ?: return MockResponse().setResponseCode(404)
                return MockResponse().setHeader("Content-Type", type).setBody(Buffer().write(body))
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun download(title: String, path: String, userAgent: String? = null): Pair<DownloadInfo, File?> = runBlocking {
        val out = File(dir, "out")
        val manager = DownloadManager(http, FolderSink(out), CoroutineScope(SupervisorJob() + Dispatchers.IO))
        val before = out.listFiles()?.toSet().orEmpty()
        val id = manager.enqueue(PlaylistEntry(title = title, url = url(path), userAgent = userAgent))
        withTimeout(30_000) { while (manager.items.value.first { it.id == id }.isActive) delay(20) }
        val created = out.listFiles()?.toSet().orEmpty() - before
        manager.items.value.first { it.id == id } to created.firstOrNull()
    }

    @Test fun directDownloadSanitizesNameAndSendsUserAgent() {
        val (info, f) = download("Clip: teste/1", "/files/clip.mp4", "Agent/9")
        assertEquals(DownloadState.COMPLETED, info.state)
        assertEquals("Clip_ teste_1.mp4", f!!.name)
        assertArrayEquals(file, f.readBytes())
        assertEquals("Agent/9", lastUserAgent)
    }

    @Test fun duplicateNameGetsSuffix() {
        download("Clip", "/files/clip.mp4")
        val (_, second) = download("Clip", "/files/clip.mp4")
        assertEquals("Clip (2).mp4", second!!.name)
    }

    @Test fun extensionFromContentType() {
        val (info, f) = download("Canal", "/live/canal")
        assertEquals(DownloadState.COMPLETED, info.state)
        assertTrue(f!!.name.endsWith(".ts"))
    }

    @Test fun hlsSegmentsJoinedInOrder() {
        val (_, f) = download("HLS", "/hls/index.m3u8")
        assertArrayEquals(segments.reduce { a, b -> a + b }, f!!.readBytes())
    }

    @Test fun hlsAes128Decrypted() {
        val (info, f) = download("HLS AES", "/enc/v/index.m3u8")
        assertEquals(info.detail, DownloadState.COMPLETED, info.state)
        assertArrayEquals(segments.reduce { a, b -> a + b }, f!!.readBytes())
    }

    @Test fun masterPicksBestVariant() {
        val (_, f) = download("Master", "/master.m3u8")
        assertArrayEquals(segments.reduce { a, b -> a + b }, f!!.readBytes())
    }

    @Test fun hlsDetectedByContentType() {
        val (_, f) = download("Detectado", "/stream?id=7")
        assertArrayEquals(segments.reduce { a, b -> a + b }, f!!.readBytes())
    }

    @Test fun error404FailsAndRemovesPartialFile() {
        val (info, f) = download("Inexistente", "/nao-existe.mp4")
        assertEquals(DownloadState.FAILED, info.state)
        assertEquals("O servidor respondeu 404", info.detail)
        assertEquals(null, f)
    }

    // ───── Listas ─────

    private fun loader() = PlaylistLoader(File(dir, "lists"), http) { throw IllegalStateException() }

    @Test fun remoteListCachedAndUsedOffline() = runBlocking {
        routes["/listas/lista.m3u"] = "audio/x-mpegurl" to "#EXTM3U\n#EXTINF:-1,Rel\n../files/clip.mp4\n#EXTINF:-1,Dois\nhttp://x/2.ts\n".toByteArray()
        val loader = loader()
        val listUrl = url("/listas/lista.m3u")
        val remote = loader.load(listUrl)
        assertEquals(PlaylistOrigin.NETWORK, remote.origin)
        assertEquals(url("/files/clip.mp4"), remote.entries[0].url)

        routes.remove("/listas/lista.m3u")
        val failed = runCatching { loader.load(listUrl) }.isFailure
        val offline = loader.loadFromCache(listUrl)
        assertTrue(failed)
        assertEquals(2, offline.entries.size)
    }

    @Test fun htmlErrorPageRejectedAndCacheKept() = runBlocking {
        routes["/listas/l2.m3u"] = "audio/x-mpegurl" to "#EXTM3U\n#EXTINF:-1,A\nhttp://x/a.ts\n".toByteArray()
        val loader = loader()
        val listUrl = url("/listas/l2.m3u")
        loader.load(listUrl)
        routes["/listas/l2.m3u"] = "text/html" to "<html><head><title>Debug Mode</title></head></html>".toByteArray()
        val rejected = runCatching { loader.load(listUrl) }.exceptionOrNull() is InvalidPlaylistException
        assertTrue(rejected)
        assertEquals(1, loader.loadFromCache(listUrl).entries.size)
    }

    @Test fun sendsVlcUserAgentForLists() = runBlocking {
        routes["/listas/ua.m3u"] = "audio/x-mpegurl" to "#EXTM3U\nhttp://x/a.ts\n".toByteArray()
        loader().load(url("/listas/ua.m3u"))
        val request = generateSequence { server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS) }.last { it.path == "/listas/ua.m3u" }
        assertTrue(request.getHeader("User-Agent")!!.startsWith("VLC/"))
    }

    @Test fun latin1ListDecoded() {
        val f = File(dir, "latin.m3u").apply {
            writeBytes("#EXTM3U\n#EXTINF:-1 group-title=\"Músicas\",Canção do Ano\nhttp://x/a.mp3\n".toByteArray(Charsets.ISO_8859_1))
        }
        val entries = PlaylistLoader.parseFile(f, null)
        assertEquals("Canção do Ano", entries[0].title)
        assertEquals("Músicas", entries[0].group)
    }

    @Test fun hlsUrlDetection() {
        assertTrue(DownloadManager.looksLikeHls("http://a/b/index.m3u8"))
        assertTrue(DownloadManager.looksLikeHls("http://a/get.php?type=m3u8"))
        assertFalse(DownloadManager.looksLikeHls("http://a/b/123.ts"))
    }
}
