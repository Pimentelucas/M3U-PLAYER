package com.m3uplayer.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m3uplayer.app.download.downloadFolderDescription
import com.m3uplayer.app.ui.MainViewModel
import com.m3uplayer.app.ui.theme.AppColors
import java.net.URI

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val saved by vm.savedSources.collectAsState()
    val status by vm.listStatus.collectAsState()
    val trusted by vm.trustedHosts.collectAsState()
    var url by rememberSaveable { mutableStateOf(vm.activeSource?.takeIf { it.startsWith("http", true) } ?: "") }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            // Mantém a permissão de leitura para poder atualizar a lista depois.
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            vm.open(uri.toString())
            onBack()
        }
    }
    val load = {
        if (url.isNotBlank()) {
            vm.open(url.trim())
            onBack()
        }
    }

    Scaffold(
        containerColor = AppColors.Bg,
        topBar = {
            TopAppBar(
                title = { Text("Listas") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.Bg),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("Cole a URL ou escolha um arquivo. A lista fica salva e abre sozinha da próxima vez.", color = AppColors.Muted)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("URL da lista M3U") },
                placeholder = { Text("http://...") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { load() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = load, enabled = url.isNotBlank()) { Text("Carregar") }
                OutlinedButton(onClick = { openFile.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Abrir arquivo")
                }
            }
            OutlinedButton(onClick = { vm.refresh(); onBack() }, Modifier.padding(top = 4.dp), enabled = vm.activeSource != null) {
                Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Atualizar lista agora")
            }
            if (status.isNotEmpty()) Text(status, Modifier.padding(top = 8.dp), color = AppColors.Muted, style = MaterialTheme.typography.bodySmall)

            if (saved.isNotEmpty()) {
                Text("Listas salvas", Modifier.padding(top = 24.dp, bottom = 4.dp), fontWeight = FontWeight.SemiBold)
                for (source in saved) {
                    val active = source.equals(vm.activeSource, ignoreCase = true)
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.open(source); onBack() }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (active) Icon(Icons.Default.CheckCircle, "Ativa", tint = AppColors.Accent, modifier = Modifier.size(20.dp))
                        else Spacer(Modifier.width(20.dp))
                        Text(displaySource(source), Modifier.padding(start = 12.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { vm.forgetSource(source) }) { Icon(Icons.Default.Close, "Remover") }
                    }
                    HorizontalDivider()
                }
            }

            if (trusted.isNotEmpty()) {
                Text("Servidores confiáveis", Modifier.padding(top = 24.dp), fontWeight = FontWeight.SemiBold)
                Text("O certificado HTTPS destes servidores não é verificado.", color = AppColors.Muted,
                    style = MaterialTheme.typography.bodySmall)
                for (host in trusted.sorted()) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(host, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { vm.untrustHost(host) }) { Icon(Icons.Default.Close, "Deixar de confiar") }
                    }
                    HorizontalDivider()
                }
            }

            Text("Downloads", Modifier.padding(top = 24.dp), fontWeight = FontWeight.SemiBold)
            Text("Os arquivos baixados ficam em ${downloadFolderDescription()}.", Modifier.padding(top = 4.dp), color = AppColors.Muted)
        }
    }
}

/** Mostra a URL sem a query (que costuma ter usuário e senha do IPTV). */
fun displaySource(source: String): String {
    if (source.startsWith("http", true)) {
        return runCatching {
            val u = URI(source)
            u.host + (u.path ?: "") + if (!u.rawQuery.isNullOrEmpty()) "?…" else ""
        }.getOrDefault(source)
    }
    if (source.startsWith("content://")) {
        return Uri.decode(source.substringAfterLast('/')).substringAfterLast(':').substringAfterLast('/')
    }
    return source
}
