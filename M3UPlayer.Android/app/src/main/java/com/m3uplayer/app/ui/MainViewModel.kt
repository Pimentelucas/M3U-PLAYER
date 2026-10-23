package com.m3uplayer.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.m3uplayer.app.M3UApp
import com.m3uplayer.app.data.MediaLibrary
import com.m3uplayer.app.data.Tls
import com.m3uplayer.app.data.WatchRecord
import com.m3uplayer.app.download.DownloadManager
import com.m3uplayer.app.download.DownloadScanner
import com.m3uplayer.app.download.folderLabel
import com.m3uplayer.app.model.MediaItem
import com.m3uplayer.app.model.MediaKind
import com.m3uplayer.app.model.PlaylistEntry
import com.m3uplayer.app.model.SeriesInfo
import com.m3uplayer.app.util.Format
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Tab(val kind: MediaKind?) { HOME(null), LIVE(MediaKind.LIVE), MOVIES(MediaKind.MOVIE), SERIES(MediaKind.SERIES), DOWNLOADS(null) }

/** Mensagem grande no meio da tela (carregando / vazio / erro). */
data class ScreenMessage(val title: String, val text: String, val loading: Boolean = false, val showAddButton: Boolean = false)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as M3UApp
    private val settings = app.settings
    private val loader = app.loader
    val history = app.history
    val downloads = app.downloads

    private val _library = MutableStateFlow<MediaLibrary?>(null)
    val library: StateFlow<MediaLibrary?> = _library.asStateFlow()

    private val _message = MutableStateFlow<ScreenMessage?>(null)
    val message: StateFlow<ScreenMessage?> = _message.asStateFlow()

    private val _listStatus = MutableStateFlow("")
    val listStatus: StateFlow<String> = _listStatus.asStateFlow()

    private val _savedSources = MutableStateFlow(settings.savedSources)
    val savedSources: StateFlow<List<String>> = _savedSources.asStateFlow()
    val activeSource: String? get() = settings.lastSource

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    // Estado da navegação da biblioteca (sobrevive à troca de telas)
    val tab = MutableStateFlow(Tab.HOME)
    val chipGroup = MutableStateFlow<String?>(null)
    val showingContinue = MutableStateFlow(false)
    val query = MutableStateFlow("")

    private var loadJob: Job? = null

    /** Servidor cujo certificado HTTPS falhou — a tela pergunta se o usuário confia nele. */
    private val _certProblemHost = MutableStateFlow<String?>(null)
    val certProblemHost: StateFlow<String?> = _certProblemHost.asStateFlow()

    private val _trustedHosts = MutableStateFlow(settings.trustedHosts)
    val trustedHosts: StateFlow<Set<String>> = _trustedHosts.asStateFlow()

    /** O usuário confiou no servidor: passa a aceitar o certificado dele e tenta de novo. */
    fun trustHost(host: String) {
        settings.trustHost(host)
        _trustedHosts.value = settings.trustedHosts
        _certProblemHost.value = null
        settings.lastSource?.let { open(it, forceRefresh = true) }
    }

    /**
     * Mostra na aba Downloads tudo o que já está nas pastas de download, inclusive o baixado em outras
     * vezes que o app foi aberto (com imagem e série quando o arquivo é de um título da lista).
     */
    fun refreshDownloadedFiles() {
        val library = _library.value
        viewModelScope.launch(Dispatchers.IO) {
            val found = runCatching {
                DownloadScanner.find(app, listOf(settings.moviesFolder, settings.seriesFolder))
                    .map { DownloadScanner.infoFor(it, library) }
            }.getOrNull() ?: return@launch
            downloads.showExisting(found)
        }
    }

    // ───── Pastas dos downloads ─────

    /** Texto das pastas atuais (filmes e canais, séries) para a aba Downloads. */
    data class Folders(val movies: String, val series: String)

    private fun currentFolders() = Folders(
        folderLabel(app, settings.moviesFolder),
        folderLabel(app, settings.seriesFolder ?: settings.moviesFolder),
    )

    private val _folders = MutableStateFlow(currentFolders())
    val folders: StateFlow<Folders> = _folders.asStateFlow()

    /** Pasta escolhida no seletor do Android: guarda a permissão para gravar nela depois. */
    fun setDownloadFolder(series: Boolean, tree: Uri) {
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure {
            toast("Não foi possível usar essa pasta")
            return
        }
        if (series) settings.seriesFolder = tree.toString() else settings.moviesFolder = tree.toString()
        _folders.value = currentFolders()
        refreshDownloadedFiles()
        toast(if (series) "Os próximos episódios vão para ${_folders.value.series}" else "Os próximos filmes e canais vão para ${_folders.value.movies}")
    }

    /** Som da prévia (o que o usuário escolheu na última vez vale para as próximas). */
    var previewMuted: Boolean
        get() = settings.previewMuted
        set(value) { settings.previewMuted = value }

    fun untrustHost(host: String) {
        settings.untrustHost(host)
        _trustedHosts.value = settings.trustedHosts
    }

    fun dismissCertProblem() {
        _certProblemHost.value = null
    }

    init {
        val last = settings.lastSource
        if (last == null) {
            _message.value = ScreenMessage("Nenhuma lista ainda",
                "Adicione a URL ou o arquivo da sua lista M3U. Ela fica salva e abre sozinha nas próximas vezes.", showAddButton = true)
        } else {
            open(last)
        }
    }

    fun toast(text: String) {
        _toasts.tryEmit(text)
    }

    // ───────────────────────── Listas ─────────────────────────

    /**
     * Abre uma lista: usa a cópia salva (instantâneo) quando existe e atualiza em segundo plano se
     * ela estiver velha; sem cópia, baixa. Se a rede falhar, cai para a cópia salva.
     */
    fun open(source: String, forceRefresh: Boolean = false) {
        val src = source.trim().trim('"')
        if (src.isEmpty()) return
        loadJob?.cancel()
        settings.rememberSource(src)
        _savedSources.value = settings.savedSources

        loadJob = viewModelScope.launch {
            val cachedAt = loader.cacheTime(src)
            try {
                if (cachedAt != null && !forceRefresh) {
                    if (_library.value == null) _message.value = ScreenMessage("Abrindo sua lista...", "Usando a cópia salva no aparelho", loading = true)
                    val cached = loader.loadFromCache(src)
                    apply(cached.entries)
                    setStatus(cached.entries.size, cachedAt)
                    val old = System.currentTimeMillis() - cachedAt > settings.refreshHours * 3_600_000L
                    if (old && loader.isUrl(src)) refreshInBackground(src, cached.entries.size)
                    return@launch
                }

                _message.value = ScreenMessage(if (loader.isUrl(src)) "Baixando sua lista..." else "Abrindo sua lista...",
                    "Isso só é demorado na primeira vez", loading = true)
                _listStatus.value = "Carregando..."
                val result = loader.load(src) { bytes ->
                    _message.value = _message.value?.copy(text = "${Format.bytes(bytes)} baixados")
                    _listStatus.value = "Baixando... ${Format.bytes(bytes)}"
                }
                apply(result.entries)
                setStatus(result.entries.size, result.cachedAt)
                if (forceRefresh) toast("Lista atualizada · ${Format.count(result.entries.size)} itens")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = DownloadManager.friendly(e)
                // Certificado HTTPS não reconhecido: pergunta se o usuário confia no servidor.
                if (Tls.isCertificateProblem(e)) Tls.hostOf(src)?.let { _certProblemHost.value = it }
                val fallback = loader.cacheTime(src)
                if (fallback != null && _library.value == null) {
                    runCatching { loader.loadFromCache(src) }.getOrNull()?.let {
                        apply(it.entries)
                        setStatus(it.entries.size, fallback)
                        toast("Não foi possível atualizar a lista ($reason). Usando a cópia salva.")
                        return@launch
                    }
                }
                _listStatus.value = "Falha ao carregar: $reason"
                if (_library.value == null) _message.value = ScreenMessage("Não foi possível carregar a lista", reason, showAddButton = true)
                else toast("Não foi possível carregar a lista: $reason")
            }
        }
    }

    private suspend fun refreshInBackground(source: String, previousCount: Int) {
        try {
            _listStatus.value += " · atualizando..."
            val result = loader.load(source)
            if (result.entries.size != previousCount) {
                apply(result.entries)
                toast("Lista atualizada · ${Format.count(result.entries.size)} itens")
            }
            setStatus(result.entries.size, result.cachedAt)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            setStatus(_library.value?.entries?.size ?: 0, loader.cacheTime(source))
        }
    }

    private suspend fun apply(entries: List<PlaylistEntry>) {
        val built = withContext(Dispatchers.Default) { MediaLibrary.build(entries) }
        _library.value = built
        refreshDownloadedFiles() // agora dá para mostrar a imagem/série dos arquivos já baixados
        _message.value = null
        chipGroup.value = null
        showingContinue.value = false
    }

    private fun setStatus(count: Int, updatedAt: Long?) {
        val groups = _library.value?.groups?.size ?: 0
        val ageMin = updatedAt?.let { (System.currentTimeMillis() - it) / 60_000 }
        val whenText = when {
            ageMin == null -> ""
            ageMin < 2 -> " · atualizada agora"
            ageMin < 24 * 60 -> " · atualizada há ${(ageMin / 60).coerceAtLeast(1)} h"
            else -> " · atualizada há ${ageMin / (24 * 60)} dias"
        }
        _listStatus.value = "${Format.count(count)} itens · $groups grupos$whenText"
    }

    fun refresh() = settings.lastSource?.let { open(it, forceRefresh = true) }

    fun forgetSource(source: String) {
        settings.forgetSource(source)
        _savedSources.value = settings.savedSources
    }

    // ───────────────────────── Continuar assistindo ─────────────────────────

    /**
     * Itens do histórico: filmes pela metade, canais vistos recentemente e, para séries,
     * o episódio em andamento (ou o próximo, se o último foi terminado).
     */
    fun continueItems(records: List<WatchRecord>, kind: MediaKind?): List<MediaItem> {
        val result = ArrayList<MediaItem>()
        val seenSeries = HashSet<String>()
        for (record in records) {
            if (kind != null && record.kind != kind) continue
            var entry: PlaylistEntry? = findEntry(record)
            if (record.seriesName != null) {
                if (!seenSeries.add(record.group + "\u0001" + record.seriesName)) continue
                if (record.completed) {
                    val current = entry ?: continue
                    entry = current.series?.nextAfter(current) ?: continue
                }
            } else if (record.completed) {
                continue
            }
            result.add(entry ?: record.toEntry())
            if (result.size >= 60) break
        }
        return result
    }

    private fun findEntry(record: WatchRecord): PlaylistEntry? {
        val matches = _library.value?.findByUrl(record.url).orEmpty()
        return matches.firstOrNull { it.group == record.group } ?: matches.firstOrNull()
    }

    fun forget(item: MediaItem) {
        when (item) {
            is PlaylistEntry -> history.remove(item.url)
            is SeriesInfo -> history.removeSeries(item.title, item.group)
        }
        history.save()
    }

    fun isInHistory(item: MediaItem, records: List<WatchRecord>): Boolean = when (item) {
        is PlaylistEntry -> records.any { it.url == item.url }
        is SeriesInfo -> records.any { it.seriesName == item.title && it.group == item.group }
    }

    /** Qual episódio o botão principal da série deve tocar: o em andamento, o próximo, ou o primeiro. */
    fun seriesResumeTarget(series: SeriesInfo, records: List<WatchRecord>): Pair<PlaylistEntry, String> {
        val byUrl = series.episodes.associateBy { it.url }
        val last = records.firstOrNull { it.url in byUrl }
        if (last != null) {
            val episode = byUrl.getValue(last.url)
            if (!last.completed) return episode to "Continuar ${episode.episodeCode}"
            series.nextAfter(episode)?.let { return it to "Assistir ${it.episodeCode}" }
        }
        val first = series.episodes.first()
        return first to "Assistir ${first.episodeCode}"
    }

    /** Itens para anterior/próximo no player: episódios da série ou itens do mesmo grupo. */
    fun siblingsOf(entry: PlaylistEntry): List<PlaylistEntry> {
        entry.series?.let { return it.episodes }
        val group = _library.value?.findGroup(entry.displayGroup)
        return if (group != null && group.entries.any { it === entry }) group.entries else listOf(entry)
    }

    // ───────────────────────── Downloads ─────────────────────────

    fun download(entry: PlaylistEntry) {
        downloads.enqueue(entry)
        toast("Baixando: ${entry.title}")
    }

    fun downloadMany(entries: List<PlaylistEntry>) {
        entries.forEach { downloads.enqueue(it) }
        toast("${entries.size} episódios adicionados aos downloads")
    }
}
