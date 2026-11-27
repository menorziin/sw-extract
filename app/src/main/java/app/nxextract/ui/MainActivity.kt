package app.nxextract.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

class MainActivity : ComponentActivity() {
    private val vm: ExtractViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val scheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) {
                    Screen(vm)
                }
            }
        }
    }

    fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun openAllFilesSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
        try {
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }
}

@Composable
private fun Screen(vm: ExtractViewModel) {
    val state by vm.state.collectAsState()
    val activity = androidx.compose.ui.platform.LocalContext.current as MainActivity

    var hasAllFiles by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasAllFiles = Environment.isExternalStorageManager()
    }
    DisposableEffect(state.running) {
        activity.keepScreenOn(state.running)
        onDispose { }
    }

    val pickKeys = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importKeys(uri)
    }
    val pickRom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.selectRom(uri)
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("NX Extract", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Extrai o ExeFS e o RomFS de NSP, XCI ou NCA, ou tira o update e deixa só o jogo base.",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (!hasAllFiles) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("O app precisa de acesso a todos os arquivos para gravar a extração.")
                    Button(onClick = { activity.openAllFilesSettings() }) { Text("Conceder acesso") }
                }
            }
        }

        StepRow(
            title = "1. Chaves",
            value = state.keysInfo ?: "Nenhum prod.keys importado",
            button = "Importar prod.keys",
            enabled = !state.running,
        ) { pickKeys.launch(arrayOf("*/*")) }

        StepRow(
            title = "2. ROM",
            value = state.romName ?: "Nenhum arquivo selecionado",
            button = "Escolher NSP/XCI",
            enabled = !state.running,
        ) { pickRom.launch(arrayOf("*/*")) }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = state.exefs, onCheckedChange = vm::setExefs, enabled = !state.running)
            Text("ExeFS")
            Spacer(Modifier.padding(horizontal = 8.dp))
            Checkbox(checked = state.romfs, onCheckedChange = vm::setRomfs, enabled = !state.running)
            Text("RomFS")
        }

        OutlinedTextField(
            value = state.outDir,
            onValueChange = vm::setOutDir,
            label = { Text("Pasta de saída") },
            singleLine = true,
            enabled = !state.running,
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.running) {
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            Text(state.progressText, style = MaterialTheme.typography.bodySmall)
            Text(
                state.current,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            OutlinedButton(onClick = vm::cancel) { Text("Cancelar") }
        } else {
            Button(
                onClick = vm::extract,
                enabled = state.romUri != null && state.keysInfo != null && hasAllFiles &&
                    (state.exefs || state.romfs),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Extrair") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = state.keepDlc, onCheckedChange = vm::setKeepDlc)
                Text("Manter DLC no NSP base")
            }
            OutlinedButton(
                onClick = vm::baseOnly,
                enabled = state.romUri != null && state.keysInfo != null && hasAllFiles,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Tirar o update (NSP só com o jogo base)") }
        }

        LogView(state.log, Modifier.weight(1f))
    }

    state.error?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::dismissMessages,
            confirmButton = { TextButton(onClick = vm::dismissMessages) { Text("OK") } },
            title = { Text("Não deu certo") },
            text = { SelectionContainer { Text(msg) } },
        )
    }
    state.finished?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::dismissMessages,
            confirmButton = { TextButton(onClick = vm::dismissMessages) { Text("OK") } },
            title = { Text("Concluído") },
            text = { SelectionContainer { Text(msg) } },
        )
    }
}

@Composable
private fun StepRow(title: String, value: String, button: String, enabled: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        OutlinedButton(onClick = onClick, enabled = enabled) { Text(button) }
    }
}

@Composable
private fun LogView(lines: List<String>, modifier: Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }
    Card(modifier.fillMaxWidth()) {
        SelectionContainer {
            LazyColumn(state = listState, modifier = Modifier.padding(8.dp)) {
                items(lines) { line ->
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}
