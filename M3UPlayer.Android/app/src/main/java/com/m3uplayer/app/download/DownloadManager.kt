package com.m3uplayer.app.download

import com.m3uplayer.app.data.Net
import com.m3uplayer.app.data.Tls
import com.m3uplayer.app.model.PlaylistEntry
import com.m3uplayer.app.util.Format
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

enum class DownloadState { QUEUED, RUNNING, COMPLETED, FAILED, CANCELED }

data class DownloadInfo(
    val id: Long,
    val title: String,
    val state: DownloadState = DownloadState.QUEUED,
    val progress: Float = 0f,
    val indeterminate: Boolean = false,
    val detail: String = "Na fila",
    val fileName: String? = null,
    val openUri: String? = null,
    // Para a aba Downloads mostrar a imagem e agrupar episódios da mesma série
    val logo: String? = null,
    val group: String = "",
    val seriesName: String? = null,
    val season: Int = 0,
    val episode: Int = 0,
    val episodeLabel: String? = null,
    val square: Boolean = false,
    /** Arquivo encontrado na pasta de downloads (baixado numa vez anterior). */
    val fromDisk: Boolean = false,
) {
    val isActive get() = state == DownloadState.QUEUED || state == DownloadState.RUNNING
    val stateText
        get() = when (state) {
            DownloadState.QUEUED -> "Na fila"
            DownloadState.RUNNING -> "Baixando"
            DownloadState.COMPLETED -> "Concluído"
            DownloadState.FAILED -> "Falhou"
            DownloadState.CANCELED -> "Cancelado"
        }
}

class HttpStatusException(val code: Int) : IOException("O servidor respondeu $code")

/**
 * Fila de downloads (no máximo [maxParallel] ao mesmo tempo). Estratégias:
 *  • arquivo comum (mp4, mkv, mp3, ts...) → download direto com progresso;
 *  • HLS (.m3u8) → baixa os segmentos em paralelo, descriptografa AES-128 e junta num único arquivo;
 *  • stream ao vivo → grava até o usuário tocar em "Parar" (o arquivo é mantido).
 */
class DownloadManager(
    private val http: Call.Factory,
    private val sink: DownloadSink,
    private val scope: CoroutineScope,
    maxParallel: Int = 3,
) {
    private val slots = Semaphore(maxParallel)
    private val _items = MutableStateFlow<List<DownloadInfo>>(emptyList())
    val items: StateFlow<List<DownloadInfo>> = _items.asStateFlow()
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val nextId = AtomicLong(1)

    /** Chamado (na thread de quem pediu o download) ao enfileirar — inicia o serviço em primeiro plano. */
    var onActiveChanged: ((Boolean) -> Unit)? = null

    val hasActive: Boolean get() = _items.value.any { it.isActive }

    fun enqueue(entry: PlaylistEntry): Long {
        val id = nextId.getAndIncrement()
        _items.update {
            // Mais recentes primeiro
            listOf(DownloadInfo(
                id, entry.title,
                logo = entry.series?.logo ?: entry.logo,
                group = entry.group,
                seriesName = entry.seriesName,
                season = entry.season,
                episode = entry.episode,
                episodeLabel = if (entry.isEpisode) "${entry.episodeCode} · ${entry.episodeHeading}" else null,
                square = entry.isSquare,
            )) + it
        }
        jobs[id] = scope.launch(Dispatchers.IO) { run(id, entry) }
        onActiveChanged?.invoke(true)
        return id
    }

    fun cancel(id: Long) {
        jobs[id]?.cancel()
    }

    fun cancelAll() = jobs.values.forEach { it.cancel() }

    /** Tira da lista os que falharam ou foram cancelados (os concluídos continuam: são arquivos na pasta). */
    fun clearFinished() = _items.update { list -> list.filter { it.state != DownloadState.FAILED && it.state != DownloadState.CANCELED } }

    /**
     * Troca os itens vindos do disco pelos encontrados agora (arquivos baixados em outras vezes).
     * Arquivos já listados por um download desta sessão não se repetem.
     */
    fun showExisting(found: List<DownloadInfo>) = _items.update { list ->
        val current = list.filter { !it.fromDisk }
        val known = current.mapNotNull { it.fileName?.lowercase() }.toHashSet()
        current + found.filter { it.fileName != null && known.add(it.fileName.lowercase()) }
    }

    private fun update(id: Long, change: (DownloadInfo) -> DownloadInfo) =
        _items.update { list -> list.map { if (it.id == id) change(it) else it } }

    private class Ctx(val id: Long, val entry: PlaylistEntry) {
        var file: SinkFile? = null
        var isLive = false
        var bytes = 0L
    }

    private suspend fun run(id: Long, entry: PlaylistEntry) {
        val ctx = Ctx(id, entry)
        try {
            slots.withPermit {
                update(id) { it.copy(state = DownloadState.RUNNING, detail = "Conectando...") }
                downloadCore(ctx)
                ctx.file?.finish()
                update(id) {
                    it.copy(state = DownloadState.COMPLETED, progress = 1f, indeterminate = false,
                        detail = "Salvo · ${Format.bytes(ctx.bytes)} · ${ctx.file?.displayName}",
                        fileName = ctx.file?.displayName, openUri = ctx.file?.openUri)
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                if (ctx.isLive && ctx.bytes > 0) {
                    ctx.file?.finish()
                    update(id) {
                        it.copy(state = DownloadState.COMPLETED, indeterminate = false, progress = 1f,
                            detail = "Gravação encerrada · ${Format.bytes(ctx.bytes)} · ${ctx.file?.displayName}",
                            fileName = ctx.file?.displayName, openUri = ctx.file?.openUri)
                    }
                } else {
                    ctx.file?.delete()
                    update(id) { it.copy(state = DownloadState.CANCELED, indeterminate = false, detail = "Cancelado") }
                }
            }
        } catch (e: Exception) {
            if (ctx.isLive && ctx.bytes > 0) ctx.file?.finish() else ctx.file?.delete()
            update(id) { it.copy(state = DownloadState.FAILED, indeterminate = false, detail = friendly(e)) }
        } finally {
            jobs.remove(id)
            // O serviço observa a lista e se encerra sozinho quando não há mais downloads ativos.
        }
    }

    private suspend fun downloadCore(ctx: Ctx) {
        val url = ctx.entry.url
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true))
            throw IOException("Só é possível baixar endereços http/https")

        if (looksLikeHls(url)) {
            downloadHls(ctx, url)
            return
        }

        execute(url, ctx.entry).use { response ->
            val finalUrl = response.request.url.toString()
            val mime = response.body?.contentType()?.let { "${it.type}/${it.subtype}" }.orEmpty()
            if (mime.contains("mpegurl", true)) {
                response.close()
                downloadHls(ctx, finalUrl)
                return
            }
            downloadDirect(ctx, response, finalUrl, mime)
        }
    }

    // ───────────────────────── Download direto ─────────────────────────

    private suspend fun downloadDirect(ctx: Ctx, response: Response, url: String, mime: String) {
        val body = response.body ?: throw IOException("Resposta vazia")
        val total = body.contentLength()
        val ext = guessExtension(response.header("Content-Disposition"), url, mime)
        val file = sink.create(fileName(ctx.entry.title, ext), mimeFor(ext), seriesFolder(ctx.entry))
        ctx.file = file
        ctx.isLive = total <= 0
        update(ctx.id) { it.copy(indeterminate = ctx.isLive) }

        val started = System.currentTimeMillis()
        var lastReport = 0L
        var lastBytes = 0L
        file.openOutput().buffered(1 shl 16).use { out ->
            body.byteStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    ctx.bytes += read
                    val now = System.currentTimeMillis()
                    if (now - lastReport >= 500) {
                        val rate = if (lastReport == 0L) 0 else (ctx.bytes - lastBytes) * 1000 / (now - lastReport)
                        lastReport = now
                        lastBytes = ctx.bytes
                        update(ctx.id) {
                            if (total > 0) it.copy(progress = ctx.bytes.toFloat() / total,
                                detail = "${Format.bytes(ctx.bytes)} de ${Format.bytes(total)} · ${Format.bytes(rate)}/s")
                            else it.copy(detail = "Gravando · ${Format.bytes(ctx.bytes)} · ${Format.duration(now - started)}")
                        }
                    }
                }
            }
        }
        if (total > 0 && ctx.bytes < total) throw IOException("A conexão caiu antes do fim do arquivo.")
    }

    // ───────────────────────── HLS ─────────────────────────

    private suspend fun downloadHls(ctx: Ctx, url: String) {
        update(ctx.id) { it.copy(detail = "Lendo playlist HLS...") }
        var (text, playlistUrl) = getText(url, ctx.entry)

        if (Hls.isMaster(text)) {
            if (Hls.hasSeparateAudio(text))
                throw IOException("Este stream tem o áudio separado do vídeo; o download não é suportado no celular.")
            val variant = Hls.pickBestVariant(text, playlistUrl) ?: throw IOException("Playlist HLS sem variantes válidas.")
            val fetched = getText(variant, ctx.entry)
            text = fetched.first
            playlistUrl = fetched.second
        }

        var playlist = Hls.parseMediaPlaylist(text, playlistUrl)
        if (playlist.hasByteRange) throw IOException("Formato HLS (byte-range) não suportado para download.")
        if (playlist.segments.isEmpty()) throw IOException("A playlist HLS não contém segmentos.")
        if (playlist.segments.any { it.key != null && it.key.method != "AES-128" })
            throw IOException("Este stream usa criptografia/DRM não suportada.")

        val first = playlist.segments[0]
        val ext = if (first.map != null) ".mp4" else when (first.uri.substringBefore('?').substringAfterLast('.', "").lowercase()) {
            "aac" -> ".aac"
            "mp4", "m4s", "m4v" -> ".mp4"
            "mp3" -> ".mp3"
            else -> ".ts"
        }
        val file = sink.create(fileName(ctx.entry.title, ext), mimeFor(ext), seriesFolder(ctx.entry))
        ctx.file = file
        ctx.isLive = !playlist.endList
        update(ctx.id) { it.copy(indeterminate = ctx.isLive) }

        val state = HlsState()
        val started = System.currentTimeMillis()
        file.openOutput().buffered(1 shl 16).use { out ->
            if (playlist.endList) {
                val total = playlist.segments.size
                writeSegments(playlist.segments, ctx, out, state) {
                    update(ctx.id) {
                        it.copy(progress = state.done.toFloat() / total, detail = "${state.done}/$total segmentos · ${Format.bytes(ctx.bytes)}")
                    }
                }
                return
            }

            // Ao vivo: recarrega a playlist e grava os segmentos novos até o usuário parar.
            var lastSequence = -1L
            while (true) {
                val fresh = playlist.segments.filter { it.sequence > lastSequence }
                if (fresh.isNotEmpty()) {
                    writeSegments(fresh, ctx, out, state) {
                        update(ctx.id) {
                            it.copy(detail = "Gravando ao vivo · ${Format.bytes(ctx.bytes)} · ${Format.duration(System.currentTimeMillis() - started)}")
                        }
                    }
                    lastSequence = fresh.last().sequence
                }
                if (playlist.endList) return
                delay((playlist.targetDuration / 2).coerceIn(1.0, 10.0).times(1000).toLong())
                playlist = Hls.parseMediaPlaylist(getText(playlistUrl, ctx.entry).first, playlistUrl)
            }
        }
    }

    private class HlsState {
        val keys = HashMap<String, ByteArray>()
        val keyLock = Mutex()
        var currentMap: String? = null
        var done = 0
    }

    /** Baixa até 6 segmentos em paralelo, mas grava estritamente na ordem. */
    private suspend fun writeSegments(
        segments: List<HlsSegment>, ctx: Ctx, out: OutputStream, state: HlsState, report: () -> Unit,
    ) = coroutineScope {
        val window = ArrayDeque<Pair<HlsSegment, Deferred<ByteArray>>>()
        var next = 0
        while (next < segments.size || window.isNotEmpty()) {
            while (window.size < PARALLEL_SEGMENTS && next < segments.size) {
                val segment = segments[next++]
                window.addLast(segment to async(Dispatchers.IO) { fetchSegment(segment, ctx.entry, state) })
            }
            val (segment, deferred) = window.removeFirst()
            val data = deferred.await()
            if (segment.map != null && segment.map != state.currentMap) {
                val init = getBytesWithRetry(segment.map, ctx.entry)
                out.write(init)
                ctx.bytes += init.size
                state.currentMap = segment.map
            }
            out.write(data)
            ctx.bytes += data.size
            state.done++
            report()
        }
    }

    private suspend fun fetchSegment(segment: HlsSegment, entry: PlaylistEntry, state: HlsState): ByteArray {
        val data = getBytesWithRetry(segment.uri, entry)
        val key = segment.key ?: return data
        val keyUri = key.uri ?: return data
        val keyBytes = state.keyLock.withLock { state.keys.getOrPut(keyUri) { getBytesWithRetry(keyUri, entry) } }
        return Hls.decryptAes128(data, keyBytes, key.iv ?: Hls.sequenceIv(segment.sequence))
    }

    // ───────────────────────── HTTP ─────────────────────────

    private fun request(url: String, entry: PlaylistEntry): Request = Request.Builder().url(url)
        .header("User-Agent", entry.userAgent?.takeIf { it.isNotBlank() } ?: Net.USER_AGENT)
        .apply { entry.referrer?.takeIf { it.isNotBlank() }?.let { header("Referer", it) } }
        .build()

    private suspend fun execute(url: String, entry: PlaylistEntry): Response = withContext(Dispatchers.IO) {
        val response = http.newCall(request(url, entry)).execute()
        if (!response.isSuccessful) {
            response.close()
            throw HttpStatusException(response.code)
        }
        response
    }

    private suspend fun getText(url: String, entry: PlaylistEntry): Pair<String, String> =
        execute(url, entry).use { (it.body?.string() ?: "") to it.request.url.toString() }

    private suspend fun getBytesWithRetry(url: String, entry: PlaylistEntry): ByteArray {
        var attempt = 1
        while (true) {
            try {
                return execute(url, entry).use { it.body?.bytes() ?: ByteArray(0) }
            } catch (e: IOException) {
                if (attempt >= 4 || (e is HttpStatusException && e.code in 400..499 && e.code != 429)) throw e
                delay(1000L * attempt)
                attempt++
            }
        }
    }

    companion object {
        private const val PARALLEL_SEGMENTS = 6

        private val mediaExtensions = setOf(
            ".mp4", ".m4v", ".mkv", ".avi", ".mov", ".webm", ".ts", ".m2ts", ".mpg", ".mpeg", ".flv", ".wmv", ".3gp",
            ".mp3", ".aac", ".m4a", ".flac", ".ogg", ".oga", ".opus", ".wav", ".wma",
        )
        private val mimeExtensions = mapOf(
            "video/mp4" to ".mp4", "video/mp2t" to ".ts", "video/x-matroska" to ".mkv", "video/webm" to ".webm",
            "video/x-msvideo" to ".avi", "video/quicktime" to ".mov", "video/x-flv" to ".flv", "video/mpeg" to ".mpg",
            "audio/mpeg" to ".mp3", "audio/mp3" to ".mp3", "audio/aac" to ".aac", "audio/aacp" to ".aac",
            "audio/mp4" to ".m4a", "audio/ogg" to ".ogg", "audio/flac" to ".flac", "audio/wav" to ".wav",
        )

        internal fun looksLikeHls(url: String): Boolean {
            val path = url.substringBefore('?')
            return path.endsWith(".m3u8", true) || url.substringAfter('?', "").contains("m3u8", true)
        }

        internal fun guessExtension(contentDisposition: String?, url: String, mime: String): String {
            val fromDisposition = contentDisposition?.let { Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)""", RegexOption.IGNORE_CASE).find(it) }
                ?.groupValues?.get(1)?.substringAfterLast('.', "")?.let { ".${it.lowercase()}" }
            if (fromDisposition in mediaExtensions) return fromDisposition!!
            val fromUrl = url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").let { ".${it.lowercase()}" }
            if (fromUrl in mediaExtensions) return fromUrl
            mimeExtensions[mime.lowercase()]?.let { return it }
            return ".ts" // típico de canais IPTV sem extensão na URL
        }

        internal fun mimeFor(ext: String): String = when (ext) {
            ".mp4", ".m4v" -> "video/mp4"
            ".ts", ".m2ts" -> "video/mp2t"
            ".mkv" -> "video/x-matroska"
            ".webm" -> "video/webm"
            ".avi" -> "video/x-msvideo"
            ".mov" -> "video/quicktime"
            ".mp3" -> "audio/mpeg"
            ".aac" -> "audio/aac"
            ".m4a" -> "audio/mp4"
            ".ogg", ".oga", ".opus" -> "audio/ogg"
            ".flac" -> "audio/flac"
            ".wav" -> "audio/wav"
            else -> "application/octet-stream"
        }

        /** Episódios ficam numa subpasta com o nome da série. */
        internal fun seriesFolder(entry: PlaylistEntry): String? = entry.seriesName?.let { fileName(it, "") }

        internal fun fileName(title: String, ext: String): String {
            val clean = title.map { if (it in "\\/:*?\"<>|" || it.code < 32) '_' else it }.joinToString("")
                .trim().trimEnd('.').take(120).trim()
            return (clean.ifEmpty { "midia" }) + ext
        }

        fun friendly(e: Throwable): String = when {
            Tls.isCertificateProblem(e) -> "O certificado de segurança (HTTPS) do servidor não é reconhecido pelo Android"
            else -> friendlyBasic(e)
        }

        private fun friendlyBasic(e: Throwable): String = when (e) {
            is HttpStatusException -> "O servidor respondeu ${e.code}"
            is UnknownHostException -> "Sem conexão ou servidor não encontrado"
            is java.net.ConnectException -> "Não foi possível conectar ao servidor (ele pode estar fora do ar)"
            is SocketTimeoutException -> "O servidor demorou demais para responder"
            else -> if (e.message?.contains("unexpected end of stream", ignoreCase = true) == true)
                "A conexão com o servidor caiu no meio do caminho. Tente de novo."
            else e.message ?: e.javaClass.simpleName
        }
    }
}
