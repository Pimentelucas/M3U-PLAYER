package com.m3uplayer.app

import com.m3uplayer.app.data.PlaylistLoader
import com.m3uplayer.app.data.Tls
import com.m3uplayer.app.data.TrustAwareCallFactory
import com.m3uplayer.app.download.DownloadManager
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.nio.file.Files

/** Servidor HTTPS com certificado autoassinado — o caso de muitos servidores IPTV. */
class TlsTest {
    private val server = MockWebServer()
    private val dir: File = Files.createTempDirectory("tls").toFile()

    @Before
    fun setUp() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setHeader("Content-Type", "audio/x-mpegurl").setBody("#EXTM3U\n#EXTINF:-1,Canal\nhttp://x/1.ts\n")
        }
        server.start(InetAddress.getByName("localhost"), 0)
    }

    @After
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    @Test
    fun selfSignedFailsUntilServerIsTrusted() = runBlocking {
        val url = server.url("/lista.m3u").toString()
        val trusted = mutableSetOf<String>()
        val loader = PlaylistLoader(dir, TrustAwareCallFactory(OkHttpClient()) { it in trusted }) { throw IOException() }

        val error = runCatching { loader.load(url) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue("deveria ser erro de certificado: $error", Tls.isCertificateProblem(error!!))
        assertEquals("O certificado de segurança (HTTPS) do servidor não é reconhecido pelo Android", DownloadManager.friendly(error))

        trusted += Tls.hostOf(url)!!
        assertEquals(1, loader.load(url).entries.size)
    }

    @Test
    fun otherErrorsAreNotCertificateProblems() {
        assertFalse(Tls.isCertificateProblem(IOException("O servidor respondeu 404")))
        assertEquals("example.com", Tls.hostOf("https://Example.com:8080/get.php?u=a&p=b"))
    }
}
