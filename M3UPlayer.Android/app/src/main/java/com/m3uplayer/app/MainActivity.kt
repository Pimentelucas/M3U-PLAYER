package com.m3uplayer.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.m3uplayer.app.data.MediaDescriber
import com.m3uplayer.app.data.WatchRecord
import com.m3uplayer.app.model.MediaItem
import com.m3uplayer.app.model.PlaylistEntry
import com.m3uplayer.app.model.SeriesInfo
import com.m3uplayer.app.player.PlayerActivity
import com.m3uplayer.app.ui.MainViewModel
import com.m3uplayer.app.ui.Tab
import com.m3uplayer.app.ui.components.CardAction
import com.m3uplayer.app.ui.components.PreviewCard
import com.m3uplayer.app.ui.components.previewTargetOf
import com.m3uplayer.app.ui.screens.ListsScreen
import com.m3uplayer.app.ui.screens.MainScreen
import com.m3uplayer.app.ui.screens.SeriesScreen
import com.m3uplayer.app.ui.theme.M3UTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Lista pedida por outro app/site, esperando o usuário confirmar (não troca a lista atual sozinho). */
    private var externalSource by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent) // ao girar a tela o pedido não se repete
        setContent { M3UTheme { AppNavigation() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Abrir um .m3u vindo de outro app, ou a aba Downloads a partir da notificação. */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false)) vm.tab.value = Tab.DOWNLOADS
        if (intent.action == Intent.ACTION_VIEW) intent.data?.let { uri ->
            // Sem lista ainda: abre direto. Com uma lista em uso, pergunta antes (um link de um site
            // qualquer não pode trocar a lista do usuário sem ele ver).
            if (vm.activeSource == null) vm.open(uri.toString()) else externalSource = uri.toString()
        }
    }

    /** Android 13+: pede permissão para mostrar o progresso dos downloads na notificação. */
    private fun askNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun play(entry: PlaylistEntry, fromStart: Boolean = false) =
        PlayerActivity.start(this, entry, vm.siblingsOf(entry), fromStart)

    @Composable
    private fun AppNavigation() {
        val nav = rememberNavController()
        var confirm by remember { mutableStateOf<Pair<String, List<PlaylistEntry>>?>(null) }
        var previewItem by remember { mutableStateOf<MediaItem?>(null) }
        val records by vm.history.items.collectAsState()

        fun download(entries: List<PlaylistEntry>, name: String) {
            if (entries.size > 20) {
                confirm = name to entries
                return
            }
            askNotificationsIfNeeded()
            if (entries.size == 1) vm.download(entries[0]) else vm.downloadMany(entries)
        }

        fun openItem(item: MediaItem) = when (item) {
            is SeriesInfo -> nav.navigate("series/${Uri.encode(item.displayGroup)}/${Uri.encode(item.title)}")
            is PlaylistEntry -> play(item)
        }

        fun actions(item: MediaItem, records: List<WatchRecord>): List<CardAction> = buildList {
            when (item) {
                is SeriesInfo -> {
                    add(CardAction("Ver episódios") { openItem(item) })
                    add(CardAction("Baixar todos os episódios (${item.episodes.size})") { download(item.episodes, item.title) })
                }
                is PlaylistEntry -> {
                    add(CardAction("Reproduzir") { play(item) })
                    val record = records.firstOrNull { it.url == item.url }
                    if (record?.canResume == true) add(CardAction("Assistir do início") { play(item, fromStart = true) })
                    add(CardAction("Baixar") { download(listOf(item), item.title) })
                    add(CardAction("Copiar URL") {
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("URL", item.url))
                        vm.toast("URL copiada")
                    })
                }
            }
            if (vm.isInHistory(item, records)) add(CardAction("Remover de \"Continuar assistindo\"") { vm.forget(item) })
        }

        NavHost(nav, startDestination = "main") {
            composable("main") {
                // Tocar (ou tocar e segurar) numa mídia abre o card grande da prévia; de lá, outro toque abre a mídia.
                MainScreen(vm, onOpenItem = { previewItem = it }, onPreview = { previewItem = it }, onOpenLists = { nav.navigate("lists") })
            }
            composable("lists") {
                ListsScreen(vm, onBack = { nav.popBackStack() })
            }
            composable(
                "series/{group}/{title}",
                arguments = listOf(navArgument("group") { type = NavType.StringType }, navArgument("title") { type = NavType.StringType }),
            ) { entry ->
                val group = entry.arguments?.getString("group").orEmpty()
                val title = entry.arguments?.getString("title").orEmpty()
                val series = vm.library.value?.findSeries(group, title)
                if (series == null) {
                    nav.popBackStack()
                } else {
                    SeriesScreen(vm, series, onBack = { nav.popBackStack() }, onPlay = { play(it) },
                        onDownloadSeason = { download(it, "${series.title} T${it.firstOrNull()?.season ?: ""}") })
                }
            }
        }

        // Prévia (card grande ao tocar numa mídia)
        previewItem?.let { item ->
            val target = previewTargetOf(item) { series -> vm.seriesResumeTarget(series, records).first }
            val record = target?.let { t -> records.firstOrNull { it.url == t.url } }
            val description = remember(item, record) { MediaDescriber.describe(item, record, if (item is SeriesInfo) target else null) }
            val close = { previewItem = null }
            PreviewCard(
                item = item,
                playEntry = target,
                record = record,
                meta = description.meta,
                playLabel = description.playLabel,
                callFactory = (application as M3UApp).callFactory,
                initiallyMuted = vm.previewMuted,
                onMutedChange = { vm.previewMuted = it },
                otherActions = actions(item, records)
                    .filterNot { it.label == "Reproduzir" || it.label == "Baixar" || it.label == "Ver episódios" }
                    .map { action -> CardAction(action.label) { close(); action.onClick() } },
                onPlay = { close(); target?.let { play(it) } },
                onDownload = if (item is PlaylistEntry) ({ close(); download(listOf(item), item.title) }) else null,
                onEpisodes = if (item is SeriesInfo) ({ close(); openItem(item) }) else null,
                onDismiss = close,
            )
        }

        externalSource?.let { source ->
            val uri = Uri.parse(source)
            val from = if (uri.scheme == "http" || uri.scheme == "https") "do servidor ${uri.host}" else "de um arquivo"
            AlertDialog(
                onDismissRequest = { externalSource = null },
                title = { Text("Abrir esta lista?") },
                text = { Text("Outro app pediu para abrir uma lista $from. Ela vai substituir a lista que você está usando agora.") },
                confirmButton = {
                    TextButton(onClick = {
                        externalSource = null
                        vm.open(source)
                    }) { Text("Abrir") }
                },
                dismissButton = { TextButton(onClick = { externalSource = null }) { Text("Cancelar") } },
            )
        }

        confirm?.let { (name, entries) ->
            AlertDialog(
                onDismissRequest = { confirm = null },
                title = { Text("Baixar ${entries.size} itens?") },
                text = { Text("Todos os episódios de \"$name\" serão adicionados aos downloads (3 por vez).") },
                confirmButton = {
                    TextButton(onClick = {
                        confirm = null
                        askNotificationsIfNeeded()
                        vm.downloadMany(entries)
                    }) { Text("Baixar") }
                },
                dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancelar") } },
            )
        }
    }

    companion object {
        const val EXTRA_OPEN_DOWNLOADS = "openDownloads"
    }
}
