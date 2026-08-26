package com.portaretrato.app.ui

import android.os.Bundle
import android.text.format.DateFormat
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.portaretrato.app.PortaRetratoApp
import com.portaretrato.app.R
import com.portaretrato.app.databinding.ActivityPrivacyBinding
import com.portaretrato.app.security.CameraAuditEntry
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Tela de privacidade da camera.
 *
 * E a contrapartida honesta do fato de o aparelho ficar com uma camera apontada
 * para a sala da casa de alguem: a pessoa pode abrir esta tela e ver, sem
 * depender de acreditar em ninguem, quando a camera foi usada, por quanto tempo
 * e para que.
 *
 * Alcancavel por um toque na propria notificacao de camera ativa — ou seja, no
 * momento exato em que a duvida surge.
 */
class PrivacyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPrivacyBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPrivacyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.explanation.setText(R.string.privacy_explanation)

        val guard = PortaRetratoApp.from(this).cameraGuard

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                guard.status.collectLatest { status ->
                    binding.currentState.text = if (status.inUse) {
                        getString(R.string.privacy_camera_on, status.reason.orEmpty())
                    } else {
                        getString(R.string.privacy_camera_off)
                    }
                    renderHistory()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        renderHistory()
    }

    private fun renderHistory() {
        val entries = PortaRetratoApp.from(this).cameraGuard.auditLog.recent(limit = 50)
        binding.emptyHistory.visibility =
            if (entries.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        binding.historyList.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            entries.map(::describe),
        )
    }

    private fun describe(entry: CameraAuditEntry): String {
        val time = DateFormat.getTimeFormat(this).format(entry.timestamp)
        val date = DateFormat.getDateFormat(this).format(entry.timestamp)
        val what = when (entry.event) {
            CameraAuditEntry.Event.GRANTED -> "Camera ligada — ${entry.purpose.userVisibleReason}"
            CameraAuditEntry.Event.RELEASED -> {
                val seconds = (entry.durationMs ?: 0L) / 1000
                "Camera desligada — durou ${seconds}s"
            }
            CameraAuditEntry.Event.DENIED -> "Acesso negado (${entry.denialReason})"
            CameraAuditEntry.Event.PREEMPTED -> "Interrompida por uma chamada"
            CameraAuditEntry.Event.REQUESTED -> "Pedido de uso"
        }
        return "$date $time\n$what"
    }
}
