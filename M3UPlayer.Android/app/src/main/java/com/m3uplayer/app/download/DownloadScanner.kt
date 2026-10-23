package com.m3uplayer.app.download

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.m3uplayer.app.data.MediaLibrary
import com.m3uplayer.app.model.MediaKind
import com.m3uplayer.app.model.PlaylistEntry
import com.m3uplayer.app.util.Format
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Encontra as mídias já baixadas (inclusive em outras vezes que o app foi aberto) para a aba Downloads
 * mostrar tudo: Downloads/M3U Player e as pastas escolhidas para filmes e séries (com as subpastas das séries).
 */
object DownloadScanner {
    private val mediaExtensions = setOf(
        "mp4", "mkv", "ts", "avi", "mov", "m4v", "webm", "wmv", "flv", "mpg", "mpeg",
        "mp3", "m4a", "aac", "flac", "ogg", "opus", "wav",
    )
    private val duplicateSuffix = Regex("""\s\(\d{1,3}\)$""") // "Filme (2).mp4", sem confundir com "(2008)"
    private val ids = AtomicLong(-1)

    data class FoundFile(val name: String, val openUri: String, val size: Long, val modified: Long)

    /** Título a partir do nome do arquivo ("Dexter S02E03 (2).mp4" → "Dexter S02E03"). */
    fun titleOf(fileName: String): String = fileName.substringBeforeLast('.').replace(duplicateSuffix, "").trim()

    fun isMedia(fileName: String) = fileName.substringAfterLast('.', "").lowercase() in mediaExtensions

    /** Item "concluído" para um arquivo: com a imagem e o episódio do título da lista de mesmo nome, se houver. */
    fun infoFor(file: FoundFile, library: MediaLibrary?): DownloadInfo {
        val title = titleOf(file.name)
        val entry = library?.findByFileName(title)
            ?: PlaylistEntry(title = title).also { it.kind = MediaKind.MOVIE; MediaLibrary.parseEpisode(it) }
        return DownloadInfo(
            ids.getAndDecrement(), entry.title,
            state = DownloadState.COMPLETED,
            progress = 1f,
            detail = "Salvo · ${Format.bytes(file.size)} · ${file.name}",
            fileName = file.name,
            openUri = file.openUri,
            logo = entry.series?.logo ?: entry.logo,
            group = entry.group,
            seriesName = entry.seriesName,
            season = entry.season,
            episode = entry.episode,
            episodeLabel = if (entry.isEpisode) "${entry.episodeCode} · ${entry.episodeHeading}" else null,
            square = entry.isSquare,
            fromDisk = true,
        )
    }

    /** Arquivos de mídia de todas as pastas de download, mais novos primeiro. */
    fun find(context: Context, folders: List<String?>): List<FoundFile> {
        val found = LinkedHashMap<String, FoundFile>()
        fun add(f: FoundFile) { if (isMedia(f.name) && f.size > 0) found.putIfAbsent(f.openUri, f) }

        runCatching { defaultFolderFiles(context).forEach(::add) }
        for (tree in folders.filterNotNull().distinct().map(Uri::parse)) {
            if (hasAccess(context, tree)) runCatching { treeFiles(context, tree).forEach(::add) }
        }
        return found.values.sortedByDescending { it.modified }
    }

    private fun defaultFolderFiles(context: Context): List<FoundFile> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val root = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "M3U Player")
            return root.walkTopDown().maxDepth(2).filter { it.isFile }.map { file ->
                FoundFile(file.name, FileProvider.getUriForFile(context, "${context.packageName}.files", file).toString(),
                    file.length(), file.lastModified())
            }.toList()
        }
        val result = ArrayList<FoundFile>()
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.SIZE, MediaStore.Downloads.DATE_MODIFIED),
            "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.IS_PENDING} = 0",
            arrayOf(Environment.DIRECTORY_DOWNLOADS + "/M3U Player/%"),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(collection, c.getLong(0))
                result += FoundFile(c.getString(1) ?: continue, uri.toString(), c.getLong(2), c.getLong(3) * 1000)
            }
        }
        return result
    }

    /** Arquivos da pasta escolhida e das subpastas (um nível, onde ficam as séries). */
    private fun treeFiles(context: Context, tree: Uri): List<FoundFile> {
        val result = ArrayList<FoundFile>()
        fun list(docId: String, depth: Int) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            context.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth == 0) list(id, 1)
                    } else {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        result += FoundFile(c.getString(1) ?: continue, uri.toString(), c.getLong(3), c.getLong(4))
                    }
                }
            }
        }
        list(DocumentsContract.getTreeDocumentId(tree), 0)
        return result
    }
}
