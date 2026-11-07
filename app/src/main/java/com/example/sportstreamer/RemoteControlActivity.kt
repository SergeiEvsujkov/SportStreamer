package com.example.sportstreamer

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.example.sportstreamer.databinding.ActivityRemoteBinding
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * Пульт на втором телефоне: подключение к источнику и кнопки счёта.
 *
 * Тип подключения влияет только на подсказку и адрес по умолчанию:
 * в общей сети Wi-Fi адрес показывает источник, а при точке доступа это адрес шлюза.
 */
class RemoteControlActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRemoteBinding
    private var client: RemoteScoreClient? = null
    /** Открытый диалог завершения партии: закрывается, когда решение принято с любой стороны. */
    private var stateDialog: androidx.appcompat.app.AlertDialog? = null
    private var lastSetPending = false
    private var stateReceived = false
    private val reconnectHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var reconnectAttempts = 0
    private var userClosed = false

    /** Сканирование QR: код содержит адрес, порт и PIN источника. */
    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        applyQrPayload(contents)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRemoteBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rgConnectionType.setOnCheckedChangeListener { _, checkedId ->
            updateTypeHint(checkedId == R.id.rbHotspot)
        }
        updateTypeHint(hotspot = false)

        binding.btnScan.setOnClickListener { scanQr() }
        binding.btnConnect.setOnClickListener { connect() }
        binding.btnFirstPlus.setOnClickListener { sendPoint(1, true) }
        binding.btnFirstMinus.setOnClickListener { sendPoint(1, false) }
        binding.btnSecondPlus.setOnClickListener { sendPoint(2, true) }
        binding.btnSecondMinus.setOnClickListener { sendPoint(2, false) }
        updateButtons(enabled = false)
    }

    private var connectAddress = ""
    private var connectPin = ""

    private fun scanQr() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt(getString(R.string.remote_client_scan_prompt))
            setBeepEnabled(false)
            setOrientationLocked(false)
        }
        scanLauncher.launch(options)
    }

    /** Разбирает код вида `sportstreamer://remote?address=IP:PORT&pin=1234`. */
    private fun applyQrPayload(payload: String) {
        val uri = android.net.Uri.parse(payload)
        val address = uri.getQueryParameter("address")
        val pin = uri.getQueryParameter("pin")
        if (address.isNullOrBlank() || pin.isNullOrBlank()) {
            toast(getString(R.string.remote_client_bad_qr))
            return
        }
        binding.etAddress.setText(address)
        binding.etPin.setText(pin)
        connect()
    }

    private fun connect(silent: Boolean = false) {
        val address = if (silent) connectAddress else binding.etAddress.text?.toString()?.trim().orEmpty()
        val pin = if (silent) connectPin else binding.etPin.text?.toString()?.trim().orEmpty()
        if (address.isEmpty() || pin.length != 4) {
            if (!silent) toast(getString(R.string.remote_client_fill_fields))
            return
        }
        val separator = address.lastIndexOf(':')
        val host = if (separator > 0) address.substring(0, separator) else address
        val port = if (separator > 0) {
            address.substring(separator + 1).toIntOrNull() ?: RemoteScoreProtocol.PORT
        } else {
            RemoteScoreProtocol.PORT
        }

        client?.disconnect(null)
        if (!silent) connectAddress = address
        if (!silent) connectPin = pin
        binding.tvStatus.text = getString(R.string.remote_client_status_connecting)
        val newClient = RemoteScoreClient(host, port, pin, object : RemoteScoreClient.Listener {
            override fun onConnected() {
                reconnectAttempts = 0
                stateReceived = false
                binding.tvStatus.text = getString(R.string.remote_client_status_connected)
                updateButtons(enabled = true)
                // Если источник не пришлёт состояние, значит версии приложений разошлись.
                reconnectHandler.postDelayed({
                    if (!stateReceived && client != null) {
                        binding.tvStatus.text = getString(R.string.remote_client_no_state)
                    }
                }, STATE_WAIT_MS)
            }

            override fun onDisconnected(reason: String?) {
                binding.tvStatus.text = reason ?: getString(R.string.remote_client_status_disconnected)
                updateButtons(enabled = false)
                // Сами не отключались — пробуем подняться заново: на площадке связь моргает.
                if (!userClosed && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                    reconnectAttempts++
                    reconnectHandler.postDelayed({ if (!userClosed) connect(silent = true) }, RECONNECT_DELAY_MS)
                }
            }

            override fun onState(
                team1Score: Int,
                team2Score: Int,
                team1Sets: Int,
                team2Sets: Int,
                game: Int,
                setPending: Boolean,
                matchOver: Boolean,
                setsToWin: Int,
                team1Name: String,
                team2Name: String
            ) {
                stateReceived = true
                // Имена команд приходят с источника: на пульте они должны совпадать с табло.
                if (team1Name.isNotBlank()) binding.tvTeam1Name.text = team1Name
                if (team2Name.isNotBlank()) binding.tvTeam2Name.text = team2Name
                // Всего партий в матче: до 2 побед — 3 партии, до 3 побед — 5.
                val totalGames = setsToWin * 2 - 1
                binding.tvScore.text = getString(
                    R.string.remote_client_score,
                    team1Sets,
                    team2Sets,
                    team1Score,
                    team2Score,
                    game,
                    totalGames
                )
                if (setPending && !lastSetPending) {
                    showSetDecision()
                }
                if (!setPending) {
                    stateDialog?.dismiss()
                    stateDialog = null
                }
                lastSetPending = setPending
                updateButtons(enabled = !setPending && !matchOver)
            }
        })
        client = newClient
        newClient.connect()
    }

    /** Партия завершена: пульт предлагает то же решение, что и диалог на источнике. */
    private fun showSetDecision() {
        stateDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.remote_client_set_title)
            .setMessage(R.string.remote_client_set_message)
            .setCancelable(false)
            .setPositiveButton(R.string.remote_client_set_new) { _, _ ->
                client?.sendSetDecision(true)
                stateDialog = null
            }
            .setNegativeButton(R.string.remote_client_set_continue) { _, _ ->
                client?.sendSetDecision(false)
                stateDialog = null
            }
            .show()
    }

    private fun sendPoint(team: Int, add: Boolean) {
        val current = client
        if (current == null) {
            toast(getString(R.string.remote_client_not_connected))
            return
        }
        current.sendPoint(team, add)
    }

    private fun updateButtons(enabled: Boolean) {
        binding.btnFirstPlus.isEnabled = enabled
        binding.btnFirstMinus.isEnabled = enabled
        binding.btnSecondPlus.isEnabled = enabled
        binding.btnSecondMinus.isEnabled = enabled
    }

    private fun updateTypeHint(hotspot: Boolean) {
        binding.etAddress.hint = getString(
            if (hotspot) R.string.remote_client_address_hotspot else R.string.remote_client_address_wifi
        )
        // При точке доступа адрес шлюза почти всегда один и тот же — подставляем сразу.
        if (hotspot && binding.etAddress.text.isNullOrBlank()) {
            binding.etAddress.setText("192.168.43.1:${RemoteScoreProtocol.PORT}")
        }
    }

    override fun onDestroy() {
        userClosed = true
        reconnectHandler.removeCallbacksAndMessages(null)
        stateDialog?.dismiss()
        stateDialog = null
        client?.disconnect(null)
        client = null
        super.onDestroy()
    }

    private companion object {
        const val RECONNECT_DELAY_MS = 2000L
        const val MAX_RECONNECT_ATTEMPTS = 30
        const val STATE_WAIT_MS = 2000L
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
