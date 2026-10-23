package com.m3uplayer.app.download

import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class HlsKey(val method: String, val uri: String?, val iv: ByteArray?)
internal data class HlsSegment(val uri: String, val sequence: Long, val key: HlsKey?, val map: String?)

internal class MediaPlaylist {
    val segments = mutableListOf<HlsSegment>()
    var endList = false
    var targetDuration = 6.0
    var hasByteRange = false
}

/** Leitura de playlists HLS (master e de mídia) e descriptografia AES-128. */
internal object Hls {
    private val attributeRegex = Regex("""([A-Z0-9\-]+)=("[^"]*"|[^,]*)""")

    fun resolve(base: String, ref: String): String = URI(base).resolve(ref.trim().replace(" ", "%20")).toString()

    fun parseAttributes(list: String): Map<String, String> =
        attributeRegex.findAll(list).associate { it.groupValues[1].uppercase() to it.groupValues[2].trim('"') }

    fun isMaster(text: String) = text.contains("#EXT-X-STREAM-INF", ignoreCase = true)

    /** Áudio em playlist separada (EXT-X-MEDIA TYPE=AUDIO com URI): juntar exigiria remux. */
    fun hasSeparateAudio(master: String) = master.lineSequence().any {
        it.startsWith("#EXT-X-MEDIA:", true) && it.contains("TYPE=AUDIO", true) && it.contains("URI=", true)
    }

    /** Escolhe a variante de maior BANDWIDTH. */
    fun pickBestVariant(master: String, baseUrl: String): String? {
        var best: String? = null
        var bestBandwidth = -1L
        var pending = -1L
        var expectUri = false
        for (raw in master.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXT-X-STREAM-INF:", true)) {
                pending = parseAttributes(line.substring(18))["BANDWIDTH"]?.toLongOrNull() ?: 0
                expectUri = true
                continue
            }
            if (line.startsWith("#")) continue
            if (expectUri) {
                if (pending > bestBandwidth) {
                    bestBandwidth = pending
                    best = resolve(baseUrl, line)
                }
                expectUri = false
            }
        }
        return best
    }

    fun parseMediaPlaylist(text: String, baseUrl: String): MediaPlaylist {
        val playlist = MediaPlaylist()
        var sequence = 0L
        var key: HlsKey? = null
        var map: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:", true) -> sequence = line.substring(22).trim().toLongOrNull() ?: 0
                line.startsWith("#EXT-X-TARGETDURATION:", true) ->
                    line.substring(22).trim().toDoubleOrNull()?.let { playlist.targetDuration = it }
                line.startsWith("#EXT-X-ENDLIST", true) -> playlist.endList = true
                line.startsWith("#EXT-X-BYTERANGE", true) -> playlist.hasByteRange = true
                line.startsWith("#EXT-X-KEY:", true) -> {
                    val attrs = parseAttributes(line.substring(11))
                    val method = (attrs["METHOD"] ?: "NONE").uppercase()
                    key = if (method == "NONE") null
                    else HlsKey(method, attrs["URI"]?.let { resolve(baseUrl, it) }, attrs["IV"]?.let(::parseIv))
                }
                line.startsWith("#EXT-X-MAP:", true) -> {
                    val attrs = parseAttributes(line.substring(11))
                    if ("BYTERANGE" in attrs) playlist.hasByteRange = true
                    map = attrs["URI"]?.let { resolve(baseUrl, it) }
                }
                line.startsWith("#") -> Unit
                else -> playlist.segments.add(HlsSegment(resolve(baseUrl, line), sequence++, key, map))
            }
        }
        return playlist
    }

    fun parseIv(hex: String): ByteArray {
        val clean = (if (hex.startsWith("0x", true)) hex.substring(2) else hex).padStart(32, '0').takeLast(32)
        return ByteArray(16) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /** IV padrão do HLS: o número de sequência do segmento em 16 bytes big-endian. */
    fun sequenceIv(sequence: Long): ByteArray {
        val iv = ByteArray(16)
        for (i in 0 until 8) iv[15 - i] = (sequence ushr (8 * i)).toByte()
        return iv
    }

    fun decryptAes128(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(data)
        }
}
