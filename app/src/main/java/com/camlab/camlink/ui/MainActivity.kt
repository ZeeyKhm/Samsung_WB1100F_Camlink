package com.camlab.camlink.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.camlab.camlink.R
import com.camlab.camlink.databinding.ActivityMainBinding
import com.camlab.camlink.databinding.DialogIpBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: PhotoAdapter
    private val viewModel: MainViewModel by viewModels()
    private var diagnosticsProgress: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = PhotoAdapter(this) { item -> viewModel.toggle(item) }
        binding.grid.layoutManager = GridLayoutManager(this, 3)
        binding.grid.adapter = adapter
        binding.grid.setHasFixedSize(true)

        setSupportActionBar(binding.toolbar)

        binding.btnFind.setOnClickListener {
            val phase = viewModel.state.value.phase
            if (phase == Phase.Searching || phase == Phase.Browsing) viewModel.cancel()
            else viewModel.search()
        }
        binding.btnWifi.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        }
        binding.btnIp.setOnClickListener { showIpDialog() }
        binding.btnDiagnose.setOnClickListener { startDiagnostics() }
        binding.btnSelect.setOnClickListener { viewModel.toggleSelectAll() }
        binding.btnDownload.setOnClickListener { viewModel.download() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> render(state) }
            }
        }

        if (savedInstanceState == null) {
            val manual = intent?.getStringExtra(EXTRA_IP).orEmpty()
            if (manual.isNotBlank()) viewModel.connectTo(manual) else viewModel.search()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_details -> {
            showDetails()
            true
        }
        R.id.action_diagnose -> {
            startDiagnostics()
            true
        }
        R.id.action_about -> {
            showAbout()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun render(state: UiState) {
        adapter.submitItems(state.items)
        adapter.submitSelection(state.selected)

        if (state.diagnosing) {
            state.diagnosticStatus.takeIf { it.isNotBlank() }?.let { step ->
                diagnosticsProgress?.setMessage(step)
            }
        } else {
            state.diagnosticReport?.let { report ->
                diagnosticsProgress?.dismiss()
                diagnosticsProgress = null
                showDiagnosticReport(report)
                viewModel.consumeDiagnosticReport()
            }
        }

        val busy = state.phase == Phase.Searching || state.phase == Phase.Browsing
        val downloading = state.phase == Phase.Downloading

        // Keep the screen awake during a transfer or a diagnostic run so the
        // camera's access point does not drop out from under Wi-Fi power saving.
        if (downloading || state.diagnosing) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        when (state.phase) {
            Phase.Idle -> {
                binding.statusTitle.setText(R.string.status_idle_title)
                binding.statusBody.setText(R.string.status_idle_body)
            }
            Phase.Searching -> {
                binding.statusTitle.setText(R.string.status_searching_title)
                binding.statusBody.text = state.status.ifBlank {
                    getString(R.string.status_searching_body)
                }
            }
            Phase.Browsing -> {
                binding.statusTitle.text = state.device?.displayName ?: getString(R.string.app_name)
                binding.statusBody.text = state.status.ifBlank { getString(R.string.empty_loading) }
            }
            Phase.Ready, Phase.Downloading -> {
                if (state.phase == Phase.Downloading) {
                    binding.statusTitle.setText(R.string.status_downloading_title)
                    val progress = getString(
                        R.string.status_downloading_body,
                        (state.progressDone + 1).coerceAtMost(state.progressTotal.coerceAtLeast(1)),
                        state.progressTotal.coerceAtLeast(1),
                        state.status
                    )
                    binding.statusBody.text = if (state.rateBytesPerSecond > 0L) {
                        progress + " · " + getString(
                            R.string.status_speed,
                            Formatter.formatShortFileSize(this, state.rateBytesPerSecond)
                        )
                    } else {
                        progress
                    }
                } else {
                    binding.statusTitle.text = state.device?.displayName
                        ?: getString(R.string.status_connected_title, getString(R.string.app_name))
                    binding.statusBody.text = if (state.device != null) {
                        getString(
                            R.string.status_connected_body,
                            state.device.modelName.ifBlank { getString(R.string.app_name) },
                            state.device.ip.ifBlank { "—" }
                        )
                    } else {
                        ""
                    }
                }
            }
            Phase.Error -> {
                binding.statusTitle.setText(R.string.status_error_title)
                binding.statusBody.text = state.error.orEmpty()
            }
        }

        binding.progress.isVisible = busy || downloading
        if (downloading) {
            binding.progress.isIndeterminate = false
            binding.progress.max = state.progressTotal.coerceAtLeast(1)
            binding.progress.progress = state.progressDone
        } else {
            binding.progress.isIndeterminate = true
        }

        binding.btnFind.isEnabled = !downloading && !state.diagnosing
        binding.btnFind.setText(
            if (state.phase == Phase.Searching || state.phase == Phase.Browsing) R.string.btn_stop
            else R.string.btn_find
        )
        binding.btnIp.isEnabled = !busy && !downloading && !state.diagnosing
        binding.btnDiagnose.isEnabled = !busy && !downloading && !state.diagnosing

        binding.countText.text = if (state.items.isEmpty()) {
            getString(R.string.count_none)
        } else {
            getString(R.string.count_files, state.items.size)
        }

        binding.btnSelect.isVisible = state.items.isNotEmpty() && state.phase == Phase.Ready
        binding.btnSelect.setText(
            if (state.allSelected) R.string.select_none else R.string.select_all
        )

        binding.selectionText.text = if (state.items.isEmpty()) {
            getString(R.string.count_none)
        } else {
            getString(R.string.selected_n, state.selected.size)
        }

        binding.btnDownload.isEnabled = state.selected.isNotEmpty() && state.phase == Phase.Ready
        binding.btnDownload.setText(
            if (state.selected.isEmpty()) getString(R.string.download)
            else getString(R.string.download_n, state.selected.size)
        )

        val empty = state.items.isEmpty()
        val showEmpty = empty && state.phase != Phase.Error
        binding.empty.isVisible = showEmpty
        binding.grid.isVisible = !empty
        if (showEmpty) {
            binding.emptyTitle.setText(R.string.empty_title)
            binding.emptyBody.text = when (state.phase) {
                Phase.Searching -> getString(R.string.status_searching_body)
                Phase.Browsing -> getString(R.string.empty_loading)
                Phase.Error -> state.error.orEmpty()
                else -> getString(R.string.empty_body)
            }
        }

        state.summary?.let { summary ->
            val text = getString(
                R.string.summary_saved,
                summary.saved,
                summary.skipped,
                summary.failed
            ) + "\n" + getString(R.string.summary_folder)
            Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG).show()
            viewModel.consumeSummary()
        }

        state.error?.let { message ->
            if (state.phase != Phase.Error) {
                Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
                viewModel.consumeError()
            }
        }
    }

    private fun showIpDialog() {
        val dialogBinding = DialogIpBinding.inflate(layoutInflater)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val previous = prefs.getString(KEY_MANUAL_IP, "").orEmpty()
        dialogBinding.input.setText(previous)
        dialogBinding.input.setSelection(previous.length)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_ip_title)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = dialogBinding.input.text?.toString()?.trim().orEmpty()
                if (!isValidHost(value)) {
                    dialogBinding.inputLayout.error = getString(R.string.dialog_ip_error)
                    return@setOnClickListener
                }
                prefs.edit().putString(KEY_MANUAL_IP, value).apply()
                dialog.dismiss()
                viewModel.connectTo(value)
            }
        }
        dialog.show()
    }

    private fun startDiagnostics() {
        val state = viewModel.state.value
        if (state.diagnosing ||
            state.phase == Phase.Downloading ||
            state.phase == Phase.Searching ||
            state.phase == Phase.Browsing
        ) {
            return
        }

        diagnosticsProgress?.dismiss()
        val progress = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.diagnose_running_title)
            .setMessage(R.string.diagnose_running_body)
            .setNegativeButton(R.string.btn_stop, null)
            .setCancelable(false)
            .create()
        progress.setOnShowListener {
            progress.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                viewModel.cancelDiagnostics()
                progress.dismiss()
                if (diagnosticsProgress === progress) diagnosticsProgress = null
            }
        }
        diagnosticsProgress = progress
        progress.show()
        viewModel.diagnose()
    }

    private fun showDiagnosticReport(report: String) {
        val content = layoutInflater.inflate(R.layout.dialog_diagnostics, null)
        content.findViewById<TextView>(R.id.reportText).text = report

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.diagnose_report_title)
            .setView(content)
            .setPositiveButton(R.string.diagnose_copy, null)
            .setNeutralButton(R.string.diagnose_share, null)
            .setNegativeButton(R.string.diagnose_close, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(
                    ClipData.newPlainText(getString(R.string.diagnose_report_title), report)
                )
                Snackbar.make(binding.root, R.string.diagnose_copied, Snackbar.LENGTH_SHORT).show()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.diagnose_report_title))
                    putExtra(Intent.EXTRA_TEXT, report)
                }
                runCatching {
                    startActivity(Intent.createChooser(share, getString(R.string.diagnose_share)))
                }
            }
        }
        dialog.show()
    }

    private fun showDetails() {
        val device = viewModel.state.value.device
        if (device == null) {
            Snackbar.make(binding.root, R.string.details_none, Snackbar.LENGTH_SHORT).show()
            return
        }
        val body = buildString {
            append(getString(R.string.details_name)).append(": ")
                .append(device.friendlyName.ifBlank { "—" }).append('\n')
            append(getString(R.string.details_model)).append(": ")
                .append(device.modelName.ifBlank { "—" }).append('\n')
            append(getString(R.string.details_vendor)).append(": ")
                .append(device.manufacturer.ifBlank { "—" }).append('\n')
            append(getString(R.string.details_ip)).append(": ")
                .append(device.ip.ifBlank { "—" }).append('\n')
            append(getString(R.string.details_files)).append(": ")
                .append(viewModel.state.value.items.size).append('\n')
            append(getString(R.string.details_service)).append(": ")
                .append(device.contentDirectoryUrl ?: "—")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_details)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showAbout() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_about)
            .setMessage(R.string.about_text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun isValidHost(value: String): Boolean {
        val cleaned = value
            .removePrefix("http://")
            .removePrefix("https://")
            .trimEnd('/')
        if (cleaned.isBlank()) return false
        val host = cleaned.substringBefore(':')
        val port = cleaned.substringAfter(':', "")
        val hostOk = HOST_PATTERN.matches(host)
        val portOk = port.isEmpty() || (port.toIntOrNull()?.let { it in 1..65535 } == true)
        return hostOk && portOk
    }

    companion object {
        private const val EXTRA_IP = "ip"
        private const val PREFS = "camlink"
        private const val KEY_MANUAL_IP = "manual_ip"

        private val HOST_PATTERN = Regex("^[A-Za-z0-9]([A-Za-z0-9.\\-]{0,62})$")
    }
}
