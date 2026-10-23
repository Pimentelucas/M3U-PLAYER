package com.m3uplayer.app.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/** Destino dos downloads (separado para poder testar sem Android). */
interface DownloadSink {
    /** [subfolder]: nome da série, para os episódios ficarem numa pasta própria. */
    fun create(fileName: String, mimeType: String, subfolder: String? = null): SinkFile
}

interface SinkFile {
    val displayName: String
    /** URI para abrir o arquivo em outro app (null em testes). */
    val openUri: String?
    fun openOutput(): OutputStream
    fun size(): Long
    /** Torna o arquivo visível para outros apps (MediaStore). */
    fun finish()
    fun delete()
}

/** Pasta comum (Android 8/9 e testes). Escolhe um nome livre: "Filme.mp4", "Filme (2).mp4"... */
class FolderSink(private val root: File, private val uriFor: (File) -> String? = { null }) : DownloadSink {
    override fun create(fileName: String, mimeType: String, subfolder: String?): SinkFile {
        val folder = if (subfolder != null) File(root, subfolder) else root
        folder.mkdirs()
        val file = synchronized(this) {
            val base = fileName.substringBeforeLast('.')
            val ext = fileName.substringAfterLast('.', "")
            var candidate = File(folder, fileName)
            var n = 2
            while (candidate.exists()) candidate = File(folder, "$base ($n)${if (ext.isNotEmpty()) ".$ext" else ""}").also { n++ }
            candidate.createNewFile()
            candidate
        }
        return object : SinkFile {
            override val displayName = file.name
            override val openUri get() = uriFor(file)
            override fun openOutput(): OutputStream = FileOutputStream(file)
            override fun size() = file.length()
            override fun finish() = Unit
            override fun delete() { file.delete() }
        }
    }
}

/** Android 10+: grava em Downloads/M3U Player pelo MediaStore (não precisa de permissão). */
@RequiresApi(Build.VERSION_CODES.Q)
class MediaStoreSink(private val context: Context) : DownloadSink {
    override fun create(fileName: String, mimeType: String, subfolder: String?): SinkFile {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/M3U Player" + (subfolder?.let { "/$it" } ?: ""))
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Não foi possível criar o arquivo em Downloads")
        return object : SinkFile {
            override val displayName = fileName
            override val openUri = uri.toString()
            override fun openOutput(): OutputStream =
                resolver.openOutputStream(uri, "w") ?: throw IOException("Não foi possível abrir o arquivo")
            override fun size(): Long =
                resolver.query(uri, arrayOf(MediaStore.Downloads.SIZE), null, null, null)?.use {
                    if (it.moveToFirst()) it.getLong(0) else 0L
                } ?: 0L
            override fun finish() {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            }
            override fun delete() { runCatching { resolver.delete(uri, null, null) } }
        }
    }
}

fun defaultSink(context: Context): DownloadSink =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStoreSink(context)
    } else {
        val folder = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "M3U Player")
        FolderSink(folder) { file -> FileProvider.getUriForFile(context, "${context.packageName}.files", file).toString() }
    }

/**
 * Pasta escolhida pelo usuário (seletor de pastas do Android). Episódios vão para uma subpasta com o
 * nome da série; nomes repetidos ganham sufixo automaticamente ("Filme (1).mp4").
 */
class TreeSink(private val context: Context, private val tree: Uri) : DownloadSink {
    override fun create(fileName: String, mimeType: String, subfolder: String?): SinkFile {
        val resolver = context.contentResolver
        var parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        if (subfolder != null) parent = synchronized(TreeSink::class.java) { childFolder(parent, subfolder) }
        val uri = DocumentsContract.createDocument(resolver, parent, mimeType, fileName)
            ?: throw IOException("Não foi possível criar o arquivo na pasta escolhida")
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: fileName
        return object : SinkFile {
            override val displayName = name
            override val openUri = uri.toString()
            override fun openOutput(): OutputStream =
                resolver.openOutputStream(uri, "w") ?: throw IOException("Não foi possível abrir o arquivo")
            override fun size(): Long = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst()) it.getLong(0) else 0L
            } ?: 0L
            override fun finish() = Unit
            override fun delete() { runCatching { DocumentsContract.deleteDocument(resolver, uri) } }
        }
    }

    private fun childFolder(parent: Uri, name: String): Uri {
        val resolver = context.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR && c.getString(1).equals(name, ignoreCase = true))
                    return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
            }
        }
        return DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: throw IOException("Não foi possível criar a pasta \"$name\"")
    }

    companion object {
        /** Nome amigável da pasta: "primary:Movies/Filmes" → "Movies/Filmes". */
        fun label(tree: Uri): String {
            val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return "Pasta escolhida"
            val volume = id.substringBefore(':', "")
            val path = id.substringAfter(':', id)
            val base = if (volume.isEmpty() || volume == "primary") "" else "Cartão SD/"
            return (base + path).ifEmpty { "Armazenamento interno" }
        }
    }
}

/**
 * Escolhe o destino de cada download: a pasta dos filmes e canais, a das séries (com subpasta
 * por série) ou, se nenhuma foi escolhida (ou a permissão foi perdida), Downloads/M3U Player.
 */
class RoutingSink(
    private val context: Context,
    private val moviesTree: () -> String?,
    private val seriesTree: () -> String?,
) : DownloadSink {
    private val fallback by lazy { defaultSink(context) }

    override fun create(fileName: String, mimeType: String, subfolder: String?): SinkFile {
        val tree = (if (subfolder != null) seriesTree() ?: moviesTree() else moviesTree())?.let(Uri::parse)
        if (tree != null && hasAccess(context, tree)) {
            runCatching { return TreeSink(context, tree).create(fileName, mimeType, subfolder) }
        }
        return fallback.create(fileName, mimeType, subfolder)
    }
}

fun hasAccess(context: Context, tree: Uri): Boolean =
    context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }

/** Texto mostrado na aba Downloads para uma pasta escolhida (ou a padrão). */
fun folderLabel(context: Context, tree: String?): String =
    tree?.let(Uri::parse)?.takeIf { hasAccess(context, it) }?.let { TreeSink.label(it) } ?: downloadFolderDescription()

fun downloadFolderDescription(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "Downloads/M3U Player" else "pasta do app (Android/data)"
