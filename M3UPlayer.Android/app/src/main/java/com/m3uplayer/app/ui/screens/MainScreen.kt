package com.m3uplayer.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.TextButton
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m3uplayer.app.data.MediaLibrary
import com.m3uplayer.app.data.WatchRecord
import com.m3uplayer.app.model.MediaItem
import com.m3uplayer.app.ui.MainViewModel
import com.m3uplayer.app.ui.ScreenMessage
import com.m3uplayer.app.ui.Tab
import com.m3uplayer.app.ui.components.CardAction
import com.m3uplayer.app.ui.components.MediaGrid
import com.m3uplayer.app.ui.components.MediaRow
import com.m3uplayer.app.ui.theme.AppColors
import com.m3uplayer.app.util.Format
import kotlinx.coroutines.delay

private const val ROW_SIZE = 20
private const val SEARCH_LIMIT = 3000
private const val ALL_CHIP = "Todos"
private const val CONTINUE = "Continuar assistindo"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    vm: MainViewModel,
    onOpenItem: (MediaItem) -> Unit,
    /** Toque longo num card: abre a prévia (vídeo + resumo + ações). */
    onPreview: (MediaItem) -> Unit,
    onOpenLists: () -> Unit,
) {
    val tab by vm.tab.collectAsState()
    val library by vm.library.collectAsState()
    val message by vm.message.collectAsState()
    val records by vm.history.items.collectAsState()
    val downloads by vm.downloads.items.collectAsState()
    val query by vm.query.collectAsState()
    var searching by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.toasts.collect { snackbar.showSnackbar(it) } }
    BackHandler(enabled = searching) { searching = false; vm.query.value = "" }

    val certHost by vm.certProblemHost.collectAsState()
    certHost?.let { host -> TrustServerDialog(host, onTrust = { vm.trustHost(host) }, onDismiss = vm::dismissCertProblem) }

    Scaffold(
        containerColor = AppColors.Bg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (searching) {
                SearchBar(query, onQueryChange = { vm.query.value = it }, onClose = { searching = false; vm.query.value = "" })
            } else {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(AppColors.Accent), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                            Text("M3U Player", Modifier.padding(start = 10.dp), fontWeight = FontWeight.Bold)
                        }
                    },
                    actions = {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, "Buscar") }
                        IconButton(onClick = onOpenLists) { Icon(Icons.Default.PlaylistAdd, "Listas") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.Bg),
                )
            }
        },
        bottomBar = {
            NavigationBar(containerColor = AppColors.Surface) {
                val lib = library
                val tabs = buildList {
                    add(Triple(Tab.HOME, "Início", Icons.Default.Home))
                    if (lib == null || lib.hasKind(com.m3uplayer.app.model.MediaKind.LIVE)) add(Triple(Tab.LIVE, "TV", Icons.Default.LiveTv))
                    if (lib == null || lib.hasKind(com.m3uplayer.app.model.MediaKind.MOVIE)) add(Triple(Tab.MOVIES, "Filmes", Icons.Default.Movie))
                    if (lib == null || lib.hasKind(com.m3uplayer.app.model.MediaKind.SERIES)) add(Triple(Tab.SERIES, "Séries", Icons.Default.VideoLibrary))
                    add(Triple(Tab.DOWNLOADS, "Downloads", Icons.Default.Download))
                }
                val active = downloads.count { it.isActive }
                for ((t, label, icon) in tabs) {
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = {
                            vm.tab.value = t
                            vm.chipGroup.value = null
                            vm.showingContinue.value = false
                        },
                        icon = {
                            if (t == Tab.DOWNLOADS && active > 0) BadgedBox(badge = { Badge { Text("$active") } }) { NavIcon(icon) }
                            else NavIcon(icon)
                        },
                        label = { Text(label, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = AppColors.Hover),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (tab == Tab.DOWNLOADS && query.isEmpty()) {
                DownloadsContent(vm)
            } else {
                LibraryContent(vm, library, message, records, onOpenItem, onPreview, onOpenLists)
            }
        }
    }
}

@Composable
private fun NavIcon(icon: ImageVector) = Icon(icon, null)

/** Pergunta se o usuário confia num servidor cujo certificado HTTPS o Android não reconhece. */
@Composable
fun TrustServerDialog(host: String, onTrust: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Certificado não reconhecido") },
        text = {
            Text(
                "O servidor $host usa um certificado de segurança (HTTPS) que o Android não reconhece — " +
                    "é comum em servidores IPTV.\n\nSe você confia nesse servidor, o app pode deixar de verificar " +
                    "o certificado só para ele (vale para a lista, os vídeos e os downloads). Dá para desfazer em Listas."
            )
        },
        confirmButton = { TextButton(onClick = onTrust) { Text("Confiar neste servidor") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { delay(100); focus.requestFocus() }
    Row(
        Modifier.fillMaxWidth().background(AppColors.Bg).padding(top = 36.dp, start = 4.dp, end = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Fechar busca") }
        TextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Buscar filmes, séries e canais") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Default.Close, "Limpar") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = AppColors.Surface2, unfocusedContainerColor = AppColors.Surface2,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.weight(1f).focusRequester(focus),
        )
    }
}

@Composable
private fun LibraryContent(
    vm: MainViewModel,
    library: MediaLibrary?,
    message: ScreenMessage?,
    records: List<WatchRecord>,
    onOpenItem: (MediaItem) -> Unit,
    actions: (MediaItem) -> Unit,
    onOpenLists: () -> Unit,
) {
    val tab by vm.tab.collectAsState()
    val chip by vm.chipGroup.collectAsState()
    val showingContinue by vm.showingContinue.collectAsState()
    val rawQuery by vm.query.collectAsState()
    val recordMap = remember(records) { records.associateBy { it.url } }

    // Espera o usuário parar de digitar antes de buscar em centenas de milhares de itens.
    var query by remember { mutableStateOf(rawQuery.trim()) }
    LaunchedEffect(rawQuery) { delay(300); query = rawQuery.trim() }

    if (library == null) {
        MessageView(message ?: ScreenMessage("Carregando...", "", loading = true), onOpenLists)
        return
    }

    val kind = tab.kind
    val continueItems = remember(library, records, kind) { vm.continueItems(records, kind) }

    Column(Modifier.fillMaxSize()) {
        if (kind != null && query.isEmpty()) {
            val groups = remember(library, kind) { library.groups.filter { it.kind == kind }.map { it.name } }
            val chips = buildList {
                add(ALL_CHIP)
                if (continueItems.isNotEmpty()) add(CONTINUE)
                addAll(groups)
            }
            val selected = if (showingContinue) CONTINUE else chip ?: ALL_CHIP
            ChipRow(chips, selected) { picked ->
                vm.showingContinue.value = picked == CONTINUE
                vm.chipGroup.value = if (picked == ALL_CHIP || picked == CONTINUE) null else picked
            }
        }

        val back: () -> Unit = {
            if (query.isNotEmpty()) vm.query.value = ""
            vm.chipGroup.value = null
            vm.showingContinue.value = false
        }
        when {
            query.isNotEmpty() -> {
                val results = remember(library, query, kind) { library.search(query, if (tab == Tab.DOWNLOADS) null else kind, SEARCH_LIMIT) }
                if (results.isEmpty()) MessageView(ScreenMessage("Nada encontrado", "Nenhum título com “$query”."), onOpenLists)
                else MediaGrid(results, recordMap, { GridHeader("Resultados para “$query”",
                    if (results.size >= SEARCH_LIMIT) "mostrando os ${Format.count(SEARCH_LIMIT)} primeiros" else "${Format.count(results.size)} itens", null) },
                    onOpenItem, actions)
            }
            showingContinue -> {
                BackHandler(onBack = back)
                MediaGrid(continueItems, recordMap, { GridHeader(CONTINUE, "${continueItems.size} itens", back) }, onOpenItem, actions)
            }
            chip != null && library.findGroup(chip!!) != null -> {
                BackHandler(onBack = back)
                val group = library.findGroup(chip!!)!!
                MediaGrid(group.items, recordMap, { GridHeader(group.name, "${Format.count(group.items.size)} itens", back) }, onOpenItem, actions)
            }
            else -> HomeRows(vm, library, continueItems, recordMap, kind, onOpenItem, actions)
        }
    }
}

@Composable
private fun HomeRows(
    vm: MainViewModel,
    library: MediaLibrary,
    continueItems: List<MediaItem>,
    records: Map<String, WatchRecord>,
    kind: com.m3uplayer.app.model.MediaKind?,
    onOpenItem: (MediaItem) -> Unit,
    actions: (MediaItem) -> Unit,
) {
    val groups = remember(library, kind) { library.groups.filter { kind == null || it.kind == kind } }
    val state = rememberLazyListState()
    LaunchedEffect(kind, library) { state.scrollToItem(0) }
    LazyColumn(state = state, contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp), modifier = Modifier.fillMaxSize()) {
        if (continueItems.isNotEmpty()) {
            item(key = "continue") {
                MediaRow(CONTINUE, continueItems, records,
                    if (continueItems.size > ROW_SIZE) ({ vm.showingContinue.value = true }) else null, onOpenItem, actions)
            }
        }
        items(groups, key = { "g:" + it.name }) { group ->
            MediaRow(group.name, group.items, records, {
                vm.tab.value = when (group.kind) {
                    com.m3uplayer.app.model.MediaKind.LIVE -> Tab.LIVE
                    com.m3uplayer.app.model.MediaKind.MOVIE -> Tab.MOVIES
                    com.m3uplayer.app.model.MediaKind.SERIES -> Tab.SERIES
                }
                vm.chipGroup.value = group.name
                vm.showingContinue.value = false
            }, onOpenItem, actions)
        }
        if (groups.isEmpty() && continueItems.isEmpty()) {
            item { Text("Esta lista não tem itens deste tipo.", Modifier.fillMaxWidth().padding(32.dp), color = AppColors.Muted, textAlign = TextAlign.Center) }
        }
    }
}

@Composable
private fun ChipRow(chips: List<String>, selected: String, onSelect: (String) -> Unit) {
    val state = rememberLazyListState()
    LaunchedEffect(selected, chips.size) {
        val index = chips.indexOf(selected)
        if (index > 0) state.animateScrollToItem((index - 1).coerceAtLeast(0))
    }
    LazyRow(state = state, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(chips, key = { it }) { chip ->
            FilterChip(
                selected = chip == selected,
                onClick = { onSelect(chip) },
                label = { Text(chip, maxLines = 1) },
                shape = RoundedCornerShape(16.dp),
                border = null,
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = AppColors.Surface2, labelColor = AppColors.Text,
                    selectedContainerColor = AppColors.Accent, selectedLabelColor = Color.White,
                ),
            )
        }
    }
}

@Composable
fun GridHeader(title: String, count: String, onBack: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(count, style = MaterialTheme.typography.bodySmall, color = AppColors.Muted)
        }
    }
}

@Composable
fun MessageView(message: ScreenMessage, onOpenLists: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        if (message.loading) {
            CircularProgressIndicator(color = AppColors.Accent)
            Spacer(Modifier.height(20.dp))
        }
        Text(message.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (message.text.isNotEmpty()) {
            Text(message.text, Modifier.padding(top = 8.dp), color = AppColors.Muted, textAlign = TextAlign.Center)
        }
        if (message.showAddButton) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onOpenLists) {
                Icon(Icons.Default.PlaylistAdd, null)
                Spacer(Modifier.width(8.dp))
                Text("Adicionar lista")
            }
        }
    }
}
