package com.m3uplayer.app.data

import com.m3uplayer.app.model.PlaylistEntry
import java.io.BufferedReader
import java.io.StringReader
import java.net.URI
import java.net.URLDecoder

/**
 * Lê listas M3U / M3U8 "estendidas" (formato usado por IPTV, rádios, coleções de mídia).
 * Suporta #EXTINF com atributos (tvg-id, tvg-logo, group-title...), #EXTGRP e #EXTVLCOPT.
 * Lê linha a linha, para aguentar listas com centenas de milhares de itens.
 */
object M3UParser {
    private val attributeRegex = Regex("""([A-Za-z0-9_\-]+)="([^"]*)"""")
    private val schemeRegex = Regex("""^[a-zA-Z][a-zA-Z0-9+.\-]*://""")

    fun isHlsStream(text: String): Boolean =
        text.contains("#EXT-X-TARGETDURATION", ignoreCase = true) ||
            text.contains("#EXT-X-STREAM-INF", ignoreCase = true)

    fun parse(text: String, baseUri: URI?): List<PlaylistEntry> =
        BufferedReader(StringReader(text)).use { parse(it, baseUri) }

    fun parse(reader: BufferedReader, baseUri: URI?): List<PlaylistEntry> {
        val result = ArrayList<PlaylistEntry>()
        val strings = HashMap<String, String>() // grupos se repetem muito: guarda uma cópia só
        var pending: PlaylistEntry? = null
        var extGroup: String? = null

        while (true) {
            val raw = reader.readLine() ?: break
            val line = raw.trim().trimStart('﻿')
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> pending = parseExtInf(line, strings)
                line.startsWith("#EXTGRP:", ignoreCase = true) -> extGroup = strings.intern(line.substring(8).trim())
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val entry = pending ?: PlaylistEntry().also { pending = it }
                    applyVlcOption(entry, line.substring(11), strings)
                }
                line.startsWith("#") -> Unit
                else -> {
                    // Linha de URL/caminho: fecha o item atual.
                    val entry = pending ?: PlaylistEntry()
                    entry.url = resolveUrl(line, baseUri)
                    if (entry.title.isBlank()) entry.title = titleFromUrl(entry.url)
                    if (entry.group.isBlank() && extGroup != null) entry.group = extGroup!!
                    result.add(entry)
                    pending = null
                    extGroup = null
                }
            }
        }
        return result
    }

    private fun parseExtInf(line: String, strings: HashMap<String, String>): PlaylistEntry {
        val entry = PlaylistEntry()
        val colon = line.indexOf(':')
        val body = if (colon >= 0) line.substring(colon + 1) else ""

        // O título vem após a primeira vírgula que não está entre aspas.
        var comma = -1
        var inQuotes = false
        for (i in body.indices) {
            val c = body[i]
            if (c == '"') inQuotes = !inQuotes
            else if (c == ',' && !inQuotes) { comma = i; break }
        }

        val head = if (comma >= 0) body.substring(0, comma) else body
        entry.title = if (comma >= 0) body.substring(comma + 1).trim() else ""
        head.trimStart().split(' ', '\t', limit = 2).firstOrNull()?.toDoubleOrNull()?.let { entry.duration = it }

        var tvgName: String? = null
        for (m in attributeRegex.findAll(head)) {
            val value = m.groupValues[2].trim()
            if (value.isEmpty()) continue
            when (m.groupValues[1].lowercase()) {
                "group-title" -> entry.group = strings.intern(value)
                "tvg-logo", "logo" -> if (entry.logo == null) entry.logo = value
                "tvg-id" -> entry.tvgId = value
                "tvg-name" -> tvgName = value
                "user-agent", "http-user-agent" -> entry.userAgent = strings.intern(value)
                "http-referrer", "referrer" -> entry.referrer = strings.intern(value)
            }
        }
        if (entry.title.isBlank()) entry.title = tvgName ?: ""
        return entry
    }

    private fun HashMap<String, String>.intern(value: String): String = getOrPut(value) { value }

    private fun applyVlcOption(entry: PlaylistEntry, option: String, strings: HashMap<String, String>) {
        val eq = option.indexOf('=')
        if (eq <= 0) return
        val value = strings.intern(option.substring(eq + 1).trim())
        when (option.substring(0, eq).trim().lowercase()) {
            "http-user-agent" -> entry.userAgent = value
            "http-referrer", "http-referer" -> entry.referrer = value
        }
    }

    private fun resolveUrl(line: String, baseUri: URI?): String {
        if (schemeRegex.containsMatchIn(line) || line.startsWith("content:")) return line
        if (baseUri == null) return line
        return try {
            baseUri.resolve(line.replace(" ", "%20")).toString()
        } catch (_: Exception) {
            line
        }
    }

    fun titleFromUrl(url: String): String = try {
        val path = URI(url.replace(" ", "%20")).rawPath ?: url
        val name = URLDecoder.decode(path.substringAfterLast('/'), "UTF-8").substringBeforeLast('.')
        name.ifBlank { URI(url).host ?: url }
    } catch (_: Exception) {
        url.substringAfterLast('/').substringBeforeLast('.').ifBlank { url }
    }
}
