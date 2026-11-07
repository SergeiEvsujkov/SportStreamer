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

    private fun connect() {
        val address = binding.etAddress.text?.toString()?.trim().orEmpty()
        val pin = binding.etPin.text?.toString()?.trim().orEmpty()
        if (address.isEmpty() || pin.length != 4) {
            toast(getString(R.string.remote_client_fill_fields))
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
        binding.tvStatus.text = getString(R.string.remote_client_status_connecting)
        val newClient = RemoteScoreClient(host, port, pin, object : RemoteScoreClient.Listener {
            override fun onConnected() {
                binding.tvStatus.text = getString(R.string.remote_client_status_connected)
                updateButtons(enabled = true)
            }

            override fun onDisconnected(reason: String?) {
                binding.tvStatus.text = reason ?: getString(R.string.remote_client_status_disconnected)
                updateButtons(enabled = false)
            }
        })
        client = newClient
        newClient.connect()
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
        client?.disconnect(null)
        client = null
        super.onDestroy()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
