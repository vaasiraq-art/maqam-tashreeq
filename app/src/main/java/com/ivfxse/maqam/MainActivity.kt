package com.ivfxse.maqam

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.*
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Android WebView has no Web MIDI API. This activity bridges Android's native
 * android.media.midi stack into the page as navigator.requestMIDIAccess.
 *
 * Naming note (this is the usual source of confusion):
 *   Android MidiDeviceInfo "input port"  = a port you WRITE to  -> Web MIDI OUTPUT
 *   Android MidiDeviceInfo "output port" = a port you READ from -> Web MIDI INPUT
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var midi: MidiManager
    private val ui = Handler(Looper.getMainLooper())

    // portId -> open handles
    private val openDevices = HashMap<String, MidiDevice>()
    private val outPorts = HashMap<String, MidiInputPort>()   // we send here
    private val inPorts = HashMap<String, MidiOutputPort>()   // we receive here

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        midi = getSystemService(Context.MIDI_SERVICE) as MidiManager
        requestBluetoothIfNeeded()

        web = WebView(this)
        setContentView(web)
        WebView.setWebContentsDebuggingEnabled(true)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // the app stores templates + safety flag
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
        }
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                injectShim()
            }
        }
        web.addJavascriptInterface(Bridge(), "AndroidMIDI")

        midi.registerDeviceCallback(object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(info: MidiDeviceInfo) = notifyChange()
            override fun onDeviceRemoved(info: MidiDeviceInfo) = notifyChange()
        }, ui)

        web.loadUrl("file:///android_asset/index.html")
    }

    private fun requestBluetoothIfNeeded() {
        if (Build.VERSION.SDK_INT >= 31) {
            val need = arrayOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT
            ).filter { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            if (need.isNotEmpty()) ActivityCompat.requestPermissions(this, need.toTypedArray(), 1)
        }
    }

    private fun notifyChange() {
        ui.post { web.evaluateJavascript("window.__androidMidiStateChange && window.__androidMidiStateChange();", null) }
    }

    /** Stable id: device id + direction + port index. */
    private fun idOf(info: MidiDeviceInfo, dir: String, idx: Int) = "${info.id}-$dir-$idx"

    private fun nameOf(info: MidiDeviceInfo): String {
        val p = info.properties
        return p.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: listOfNotNull(
                p.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER),
                p.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
            ).joinToString(" ").ifBlank { "MIDI device ${info.id}" }
    }

    inner class Bridge {

        /** Full port list as JSON, consumed by the JS shim. */
        @JavascriptInterface
        fun listPorts(): String {
            val ins = JSONArray()
            val outs = JSONArray()
            for (info in midi.devices) {
                val name = nameOf(info)
                val mfr = info.properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER) ?: ""
                // ports we can READ from -> Web MIDI inputs
                for (i in 0 until info.outputPortCount) {
                    ins.put(JSONObject().apply {
                        put("id", idOf(info, "in", i)); put("name", name)
                        put("manufacturer", mfr); put("version", "1")
                    })
                }
                // ports we can WRITE to -> Web MIDI outputs
                for (i in 0 until info.inputPortCount) {
                    outs.put(JSONObject().apply {
                        put("id", idOf(info, "out", i)); put("name", name)
                        put("manufacturer", mfr); put("version", "1")
                    })
                }
            }
            return JSONObject().put("inputs", ins).put("outputs", outs).toString()
        }

        @JavascriptInterface
        fun openPort(portId: String): Boolean {
            val parts = portId.split("-")
            if (parts.size != 3) return false
            val devId = parts[0].toIntOrNull() ?: return false
            val dir = parts[1]
            val idx = parts[2].toIntOrNull() ?: return false
            val info = midi.devices.firstOrNull { it.id == devId } ?: return false

            if (openDevices.containsKey(portId)) return true

            midi.openDevice(info, { device ->
                if (device == null) return@openDevice
                openDevices[portId] = device
                if (dir == "out") {
                    device.openInputPort(idx)?.let { outPorts[portId] = it }
                } else {
                    device.openOutputPort(idx)?.let { op ->
                        inPorts[portId] = op
                        op.connect(object : MidiReceiver() {
                            override fun onSend(data: ByteArray, offset: Int, count: Int, ts: Long) {
                                if (count <= 0) return
                                val arr = JSONArray()
                                for (k in 0 until count) arr.put(data[offset + k].toInt() and 0xFF)
                                val js = "window.__androidMidiRx && window.__androidMidiRx(" +
                                        JSONObject.quote(portId) + "," + arr.toString() + ");"
                                ui.post { web.evaluateJavascript(js, null) }
                            }
                        })
                    }
                }
                notifyChange()
            }, ui)
            return true
        }

        /** Send raw MIDI bytes (including multi-byte SysEx) to an output port. */
        @JavascriptInterface
        fun send(portId: String, bytesJson: String): Boolean {
            val port = outPorts[portId] ?: return false
            return try {
                val a = JSONArray(bytesJson)
                val b = ByteArray(a.length()) { (a.getInt(it) and 0xFF).toByte() }
                port.send(b, 0, b.size)
                true
            } catch (e: Exception) { false }
        }

        @JavascriptInterface
        fun closeAll() { cleanup() }

        @JavascriptInterface
        fun hasMidiFeature(): Boolean =
            packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)
    }

    private fun injectShim() {
        val js = assets.open("webmidi-shim.js").bufferedReader().use { it.readText() }
        web.evaluateJavascript(js, null)
    }

    private fun cleanup() {
        outPorts.values.forEach { runCatching { it.close() } }
        inPorts.values.forEach { runCatching { it.close() } }
        openDevices.values.forEach { runCatching { it.close() } }
        outPorts.clear(); inPorts.clear(); openDevices.clear()
    }

    /**
     * SAFETY: if the page muted the instrument's internal sound (Local Control OFF)
     * and the app is closing, push Local ON back out on every channel while the
     * port is still open. Without this the keys stay silent after the app dies.
     */
    private fun panicRestore() {
        outPorts.values.forEach { port ->
            runCatching {
                val b = ByteArray(16 * 3 * 3)
                var i = 0
                for (ch in 0..15) {
                    b[i++] = (0xB0 or ch).toByte(); b[i++] = 123; b[i++] = 0   // all notes off
                    b[i++] = (0xB0 or ch).toByte(); b[i++] = 121; b[i++] = 0   // reset controllers
                    b[i++] = (0xB0 or ch).toByte(); b[i++] = 122; b[i++] = 127 // LOCAL ON
                }
                port.send(b, 0, i)
            }
        }
    }

    override fun onDestroy() {
        panicRestore()
        cleanup()
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        web.evaluateJavascript("window.dispatchEvent(new Event('pagehide'));", null)
    }
}
