package com.camlab.camlink.ui

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.camlab.camlink.R
import com.camlab.camlink.net.CameraDevice
import com.camlab.camlink.net.Diagnostics
import com.camlab.camlink.net.DlnaClient
import com.camlab.camlink.net.Downloader
import com.camlab.camlink.net.MediaItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Phase { Idle, Searching, Browsing, Ready, Downloading, Error }

data class UiState(
    val phase: Phase = Phase.Idle,
    val device: CameraDevice? = null,
    val items: List<MediaItem> = emptyList(),
    val selected: Set<String> = emptySet(),
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
    val rateBytesPerSecond: Long = 0L,
    val status: String = "",
    val error: String? = null,
    val summary: Downloader.Summary? = null,
    val diagnosing: Boolean = false,
    val diagnosticStatus: String = "",
    val diagnosticReport: String? = null
) {
    val allSelected: Boolean
        get() = items.isNotEmpty() && selected.size == items.size
}

/**
 * Owns discovery, browsing and downloading so the activity only renders state.
 * All camera traffic is read-only: SSDP, SOAP Browse and HTTP GET.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val client = DlnaClient(app)
    private val downloader = Downloader(app)
    private val diagnostics = Diagnostics(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var workJob: Job? = null
    private var diagnosticJob: Job? = null

    fun search() {
        if (_state.value.phase == Phase.Searching || _state.value.phase == Phase.Downloading) return
        if (_state.value.diagnosing) return
        workJob?.cancel()
        _state.value = UiState(
            phase = Phase.Searching,
            status = str(R.string.status_searching_body)
        )

        workJob = viewModelScope.launch {
            val devices = runCatching {
                client.discover { step ->
                    _state.update { it.copy(status = step) }
                }
            }.getOrElse { emptyList() }
            var device = devices.firstOrNull { it.contentDirectoryUrl != null }

            if (device == null && client.lastSsdpLocations.isEmpty()) {
                // SSDP is sometimes filtered by the phone or the camera. Probe the
                // Wi-Fi gateway and one common camera address before giving up.
                // If SSDP did answer, the advertised description URL was already
                // tried; probing gateways again would only add a long wait.
                for (host in client.likelyHosts().take(2)) {
                    device = runCatching {
                        client.connect(host) { step ->
                            _state.update { it.copy(status = step) }
                        }
                    }.getOrNull()
                    if (device != null) break
                }
            }

            if (device == null) {
                if (client.lastDescriptionStatus == 401 ||
                    client.lastDescriptionStatus == 403
                ) {
                    fail(R.string.error_camera_denied)
                } else {
                    fail(
                        if (devices.isEmpty()) R.string.error_no_camera
                        else R.string.error_no_content,
                        devices.firstOrNull()?.displayName.orEmpty()
                    )
                }
                return@launch
            }
            browse(device)
        }
    }

    /** Connect to a typed address such as `192.168.1.1` or `192.168.1.1:7676`. */
    fun connectTo(input: String) {
        if (_state.value.phase == Phase.Downloading) return
        if (_state.value.diagnosing) return
        workJob?.cancel()
        _state.value = UiState(
            phase = Phase.Searching,
            status = str(R.string.status_searching_body)
        )

        workJob = viewModelScope.launch {
            val device = runCatching {
                client.connect(input) { step ->
                    _state.update { it.copy(status = step) }
                }
            }.getOrNull()
            if (device == null) {
                if (client.lastDescriptionStatus == 401 ||
                    client.lastDescriptionStatus == 403
                ) {
                    fail(R.string.error_camera_denied)
                } else {
                    fail(R.string.error_manual, input)
                }
                return@launch
            }
            browse(device)
        }
    }

    private suspend fun browse(device: CameraDevice) {
        _state.update {
            it.copy(
                phase = Phase.Browsing,
                device = device,
                status = str(R.string.empty_loading)
            )
        }
        try {
            val items = client.browseAll(device) { line ->
                _state.update { it.copy(status = line) }
            }
            _state.update {
                it.copy(
                    phase = Phase.Ready,
                    items = items,
                    selected = items.map { item -> item.id }.toSet(),
                    status = ""
                )
            }
        } catch (exc: Exception) {
            fail(R.string.error_network, exc.message ?: exc.javaClass.simpleName)
        }
    }

    private fun fail(@StringRes message: Int, vararg args: Any) {
        _state.update {
            it.copy(
                phase = Phase.Error,
                status = "",
                error = str(message, *args)
            )
        }
    }

    fun toggle(item: MediaItem) {
        if (_state.value.phase == Phase.Downloading) return
        _state.update { current ->
            val selected = if (item.id in current.selected) {
                current.selected - item.id
            } else {
                current.selected + item.id
            }
            current.copy(selected = selected)
        }
    }

    fun toggleSelectAll() {
        if (_state.value.phase != Phase.Ready) return
        _state.update { current ->
            val selected = if (current.allSelected) {
                emptySet()
            } else {
                current.items.map { it.id }.toSet()
            }
            current.copy(selected = selected)
        }
    }

    fun download() {
        val current = _state.value
        if (current.phase == Phase.Downloading) return

        val chosen = current.items.filter { it.id in current.selected }
        if (chosen.isEmpty()) {
            _state.update { it.copy(error = str(R.string.error_nothing_selected)) }
            return
        }

        workJob?.cancel()
        workJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    phase = Phase.Downloading,
                    progressDone = 0,
                    progressTotal = chosen.size,
                    rateBytesPerSecond = 0L,
                    status = "",
                    error = null,
                    summary = null
                )
            }

            val summary = downloader.download(chosen) { done, total, item, rate ->
                _state.update {
                    it.copy(
                        progressDone = done,
                        progressTotal = total,
                        rateBytesPerSecond = rate,
                        status = item.title
                    )
                }
            }

            _state.update {
                it.copy(
                    phase = Phase.Ready,
                    selected = emptySet(),
                    progressDone = 0,
                    progressTotal = 0,
                    rateBytesPerSecond = 0L,
                    status = "",
                    summary = summary
                )
            }
        }
    }

    fun cancel() {
        workJob?.cancel()
        workJob = null
        downloader.cancel()
        _state.update { current ->
            if (current.device == null) {
                UiState()
            } else {
                current.copy(
                    phase = Phase.Ready,
                    progressDone = 0,
                    progressTotal = 0,
                    rateBytesPerSecond = 0L,
                    status = "",
                    summary = null
                )
            }
        }
    }

    fun consumeError() {
        _state.update { it.copy(error = null) }
    }

    fun consumeSummary() {
        _state.update { it.copy(summary = null) }
    }

    /** Probe Wi-Fi, SSDP, open ports and camera pages; never writes to the camera. */
    fun diagnose() {
        if (_state.value.diagnosing) return
        if (_state.value.phase == Phase.Downloading) return

        diagnosticJob?.cancel()
        _state.update {
            it.copy(
                diagnosing = true,
                diagnosticStatus = "",
                diagnosticReport = null
            )
        }

        diagnosticJob = viewModelScope.launch {
            val report = try {
                diagnostics.run { step ->
                    _state.update { it.copy(diagnosticStatus = step) }
                }
            } catch (exc: Exception) {
                "Diagnostics failed: ${exc.javaClass.simpleName}: ${exc.message.orEmpty()}"
            }
            _state.update {
                it.copy(
                    diagnosing = false,
                    diagnosticStatus = "",
                    diagnosticReport = report
                )
            }
        }
    }

    fun cancelDiagnostics() {
        diagnosticJob?.cancel()
        diagnosticJob = null
        _state.update { it.copy(diagnosing = false, diagnosticStatus = "") }
    }

    fun consumeDiagnosticReport() {
        _state.update { it.copy(diagnosticReport = null) }
    }

    private fun str(@StringRes id: Int, vararg args: Any): String =
        if (args.isEmpty()) getApplication<Application>().getString(id)
        else getApplication<Application>().getString(id, *args)
}
