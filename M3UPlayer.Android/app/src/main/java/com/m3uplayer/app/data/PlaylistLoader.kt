package com.m3uplayer.app.data

import com.m3uplayer.app.model.PlaylistEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

enum class PlaylistOrigin { LOCAL_FILE, NETWORK, CACHE }

class PlaylistLoadResult(val entries: List<PlaylistEntry>, val origin: PlaylistOrigin, val cachedAt: Long?)

class InvalidPlaylistException(message: String) : IOException(message)

/**
 * Carrega listas M3U de URL ou de um arquivo escolhido pelo usuário. Toda lista ganha uma cópia
 * local (pasta "lists" do app), o que permite abrir o app instantaneamente e funcionar offline.
 *
 * @param openStream abre fontes que não são http(s) (ex.: content:// escolhido no seletor de arquivos).
 */
class PlaylistLoader(
    private val cacheDir: File,
    private val http: Call.Factory,
    private val openStream: (String) -> InputStream,
) {
    fun isUrl(source: String) = source.startsWith("http://", true) || source.startsWith("https://", true)

    fun cacheFileFor(source: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(source.trim().toByteArray())
            .joinToString("") { "%02x".format(it) }.take(24)
        return File(cacheDir, "$hash.m3u")
    }

    fun cacheTime(source: String): Long? = cacheFileFor(source).takeIf { it.exists() }?.lastModified()

    /** Lê a cópia local de uma lista carregada anteriormente. */
    suspend fun loadFromCache(source: String): PlaylistLoadResult = withContext(Dispatchers.IO) {
        val file = cacheFileFor(source)
        PlaylistLoadResult(parseFile(file, baseUriOf(source)), PlaylistOrigin.CACHE, file.lastModified())
    }

    /** Baixa (ou copia) a lista, atualiza a cópia local e devolve os itens. */
    suspend fun load(source: String, onProgress: (Long) -> Unit = {}): PlaylistLoadResult = withContext(Dispatchers.IO) {
        val src = source.trim()
        val target = cacheFileFor(src)
        cacheDir.mkdirs()
        val temp = File(target.path + ".download")

        if (isUrl(src)) {
            val request = Request.Builder().url(src).header("User-Agent", Net.USER_AGENT).build()
            // Vários servidores IPTV devolvem uma página de erro para clientes desconhecidos: por isso o user-agent do VLC.
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("O servidor respondeu ${response.code}")
                val body = response.body ?: throw IOException("Resposta vazia")
                copyWithProgress(body.byteStream(), temp, onProgress)
            }
        } else {
            openStream(src).use { copyWithProgress(it, temp, onProgress) }
        }

        if (!looksLikePlaylist(temp)) {
            temp.delete()
            throw InvalidPlaylistException(
                "O servidor não devolveu uma lista M3U (a resposta parece uma página de erro). Confira a URL, usuário e senha."
            )
        }
        // Só substitui a cópia salva quando o download veio completo e válido.
        if (!temp.renameTo(target)) {
            target.delete()
            if (!temp.renameTo(target)) throw IOException("Não foi possível salvar a lista")
        }

        val origin = if (isUrl(src)) PlaylistOrigin.NETWORK else PlaylistOrigin.LOCAL_FILE
        PlaylistLoadResult(parseFile(target, baseUriOf(src)), origin, target.lastModified())
    }

    private suspend fun copyWithProgress(input: InputStream, target: File, onProgress: (Long) -> Unit) {
        target.outputStream().buffered(1 shl 16).use { out ->
            val buffer = ByteArray(1 shl 16)
            var total = 0L
            var lastReport = 0L
            while (true) {
                coroutineContext.ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                total += read
                if (total - lastReport > 256 * 1024) {
                    lastReport = total
                    onProgress(total)
                }
            }
            onProgress(total)
        }
    }

    private fun baseUriOf(source: String): URI? = if (isUrl(source)) runCatching { URI(source.trim()) }.getOrNull() else null

    companion object {
        /** Rejeita páginas HTML/JSON de erro que alguns servidores devolvem com status 200. */
        fun looksLikePlaylist(file: File): Boolean {
            val head = file.inputStream().use { input ->
                val buf = ByteArray(4096)
                val n = input.read(buf)
                if (n <= 0) return false
                String(buf, 0, n, Charsets.UTF_8)
            }.trimStart('﻿', ' ', '\r', '\n', '\t')
            if (head.isEmpty()) return false
            if (head.startsWith("#EXTM3U", true) || head.contains("#EXTINF", true)) return true
            if (head.startsWith("<") || head.startsWith("{") || head.startsWith("[")) return false
            return head.contains("://") || head.contains('/')
        }

        fun parseFile(file: File, baseUri: URI?): List<PlaylistEntry> {
            val charset = detectCharset(file)
            val head = file.inputStream().use { input ->
                val buf = ByteArray(8192)
                val n = input.read(buf).coerceAtLeast(0)
                String(buf, 0, n, charset)
            }
            if (M3UParser.isHlsStream(head)) {
                val url = baseUri?.toString() ?: file.toURI().toString()
                return listOf(PlaylistEntry(title = M3UParser.titleFromUrl(url), url = url))
            }
            return BufferedReader(InputStreamReader(file.inputStream(), charset), 1 shl 16).use {
                M3UParser.parse(it, baseUri)
            }
        }

        /** UTF-8 na grande maioria; se o começo do arquivo não for UTF-8 válido, usa Latin-1. */
        private fun detectCharset(file: File): Charset {
            val buffer = ByteArray(1 shl 20)
            val n = file.inputStream().use { it.read(buffer) }.coerceAtLeast(0)
            val check = (n - 4).coerceAtLeast(0) // ignora o fim, que pode cortar um caractere ao meio
            return try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(buffer, 0, check))
                Charsets.UTF_8
            } catch (_: CharacterCodingException) {
                Charsets.ISO_8859_1
            }
        }
    }
}

object Net {
    const val USER_AGENT = "VLC/3.0.21 LibVLC/3.0.21"
}
