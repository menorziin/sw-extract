package app.nxextract.ui

import android.app.Application
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.nxextract.core.Extractor
import app.nxextract.core.Keys
import app.nxextract.core.NxException
import app.nxextract.core.Storage
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val keysInfo: String? = null,
    val romUri: Uri? = null,
    val romName: String? = null,
    val exefs: Boolean = true,
    val romfs: Boolean = true,
    val outDir: String = File(Environment.getExternalStorageDirectory(), "NXExtract").path,
    val running: Boolean = false,
    val progress: Float = 0f,
    val progressText: String = "",
    val current: String = "",
    val log: List<String> = emptyList(),
    val finished: String? = null,
    val error: String? = null,
)

class ExtractViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val keysFile = File(app.filesDir, "prod.keys")
    private val cancel = AtomicBoolean(false)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            if (keysFile.exists()) describeKeys(Keys.parse(keysFile.readText()))
        }
    }

    private fun describeKeys(keys: Keys) {
        val info = if (keys.has("header_key")) "${keys.count()} chaves carregadas"
        else "${keys.count()} chaves, mas falta header_key"
        _state.update { it.copy(keysInfo = info) }
    }

    fun importKeys(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val text = getApplication<Application>().contentResolver.openInputStream(uri)!!
                    .use { it.readBytes().toString(Charsets.UTF_8) }
                val keys = Keys.parse(text)
                if (keys.count() == 0) {
                    _state.update { it.copy(error = "Esse arquivo não tem chaves no formato \"nome = hex\".") }
                    return@launch
                }
                keysFile.writeText(text)
                describeKeys(keys)
                _state.update { it.copy(error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Não consegui ler as chaves: ${e.message}") }
            }
        }
    }

    fun selectRom(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        var name = uri.lastPathSegment ?: "rom"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) name = c.getString(0)
        }
        _state.update { it.copy(romUri = uri, romName = name, finished = null, error = null) }
    }

    fun setExefs(v: Boolean) = _state.update { it.copy(exefs = v) }
    fun setRomfs(v: Boolean) = _state.update { it.copy(romfs = v) }
    fun setOutDir(v: String) = _state.update { it.copy(outDir = v) }
    fun dismissMessages() = _state.update { it.copy(finished = null, error = null) }

    fun cancel() = cancel.set(true)

    private fun log(msg: String) = _state.update { it.copy(log = (it.log + msg).takeLast(300)) }

    fun extract() {
        val s = _state.value
        val uri = s.romUri ?: return
        if (s.running) return
        cancel.set(false)
        _state.update {
            it.copy(running = true, progress = 0f, progressText = "", current = "", log = emptyList(),
                finished = null, error = null)
        }
        viewModelScope.launch(Dispatchers.IO) {
            val started = SystemClock.elapsedRealtime()
            try {
                if (!keysFile.exists()) throw NxException("Importe o prod.keys primeiro.")
                val keys = Keys.parse(keysFile.readText())
                val out = File(s.outDir)
                if (!out.isDirectory && !out.mkdirs()) {
                    throw NxException("Não consegui criar a pasta $out. Dê a permissão de acesso a todos os arquivos.")
                }
                var lastUi = 0L
                val listener = object : Extractor.Listener {
                    override fun log(message: String) = this@ExtractViewModel.log(message)

                    override fun progress(done: Long, total: Long, current: String) {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastUi < 150 && done != total) return
                        lastUi = now
                        val secs = (now - started) / 1000.0
                        val speed = if (secs > 0) done / secs else 0.0
                        _state.update {
                            it.copy(
                                progress = if (total > 0) done.toFloat() / total else 0f,
                                progressText = "${human(done)} de ${human(total)} · ${human(speed.toLong())}/s",
                                current = current,
                            )
                        }
                    }

                    override fun isCancelled() = cancel.get()
                }
                val pfd = getApplication<Application>().contentResolver.openFileDescriptor(uri, "r")
                    ?: throw NxException("Não consegui abrir o arquivo.")
                pfd.use {
                    FileInputStream(it.fileDescriptor).channel.use { ch ->
                        val opts = Extractor.Options().apply { exefs = s.exefs; romfs = s.romfs }
                        val result = Extractor(keys, listener).extract(Storage.FileStorage(ch), s.romName ?: "rom", out, opts)
                        val secs = (SystemClock.elapsedRealtime() - started) / 1000
                        val msg = if (result.cancelled) "Cancelado."
                        else "Pronto em ${secs}s:\n" + result.outputs.joinToString("\n") { f -> f.path }
                        log(msg)
                        _state.update { st -> st.copy(finished = msg) }
                    }
                }
            } catch (e: NxException) {
                _state.update { it.copy(error = e.message) }
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                val hint = if (msg.contains("ENOSPC") || msg.contains("No space")) " (sem espaço livre)" else ""
                _state.update { it.copy(error = "Erro: $msg$hint") }
            } finally {
                _state.update { it.copy(running = false) }
            }
        }
    }

    companion object {
        fun human(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val units = arrayOf("KB", "MB", "GB", "TB")
            var v = bytes.toDouble()
            var i = -1
            do {
                v /= 1024; i++
            } while (v >= 1024 && i < units.size - 1)
            return String.format(java.util.Locale.ROOT, "%.1f %s", v, units[i])
        }
    }
}
