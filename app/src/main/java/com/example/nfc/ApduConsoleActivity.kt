package com.example.nfc

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

class ApduConsoleActivity : ComponentActivity() {
    private var nfcAdapter: NfcAdapter? = null
    private var currentTag: Tag? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Toast.makeText(this, "NFC no disponible", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        enableReaderMode()
        setContent {
            NfcTheme {
                ApduConsoleScreen(
                    tagProvider = { currentTag },
                    onRefreshTag = { enableReaderMode() },
                    onBack = { finish() }
                )
            }
        }
    }

    private fun enableReaderMode() {
        val flags = NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_NFC_BARCODE
        nfcAdapter?.enableReaderMode(this, { tag ->
            currentTag = tag
            runOnUiThread {
                Toast.makeText(this, "✅ Tarjeta detectada. Ya puedes enviar comandos.", Toast.LENGTH_SHORT).show()
            }
        }, flags, Bundle())
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        nfcAdapter?.disableReaderMode(this)
    }
}

@Composable
fun ApduConsoleScreen(tagProvider: () -> Tag?, onRefreshTag: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var commandHex by remember { mutableStateOf("00 A4 00 00 02 3F 00") }
    var responseHex by remember { mutableStateOf("") }
    var responseText by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var tag by remember { mutableStateOf(tagProvider()) }

    // Actualizar tag cuando cambie
    LaunchedEffect(tagProvider()) {
        tag = tagProvider()
    }

    fun sendApdu(customCommand: String? = null) {
        val currentTag = tag ?: run {
            responseHex = "⚠️ No hay tarjeta detectada. Acerca la tarjeta al teléfono."
            statusText = "Asegúrate de tener NFC activado y acerca la tarjeta."
            return
        }
        if (isSending) return
        isSending = true
        responseHex = ""
        responseText = ""
        statusText = ""

        val cmdToSend = customCommand ?: commandHex
        val commandBytes = try {
            cmdToSend.hexToByteArray()
        } catch (error: Exception) {
            responseHex = "Error: comando HEX inválido"
            statusText = error.message.orEmpty()
            isSending = false
            return
        }

        Thread {
            var isoDep: IsoDep? = null
            try {
                isoDep = IsoDep.get(currentTag)
                isoDep?.connect()
                isoDep?.timeout = 3000
                val response = isoDep?.transceive(commandBytes)

                (context as? ComponentActivity)?.runOnUiThread {
                    if (response == null) {
                        responseHex = "Respuesta nula"
                    } else {
                        responseHex = response.toHexString()
                        responseText = response.toPrintableText()
                        statusText = response.statusDescription()
                    }
                }
            } catch (e: Exception) {
                (context as? ComponentActivity)?.runOnUiThread {
                    responseHex = "Error: ${e.message}"
                    statusText = "Mantén la tarjeta pegada. Si persiste, pulsa 'Renovar tag' y vuelve a acercar la tarjeta."
                }
            } finally {
                runCatching { isoDep?.close() }
                (context as? ComponentActivity)?.runOnUiThread { isSending = false }
            }
        }.start()
    }

    fun autoExplore() {
        val currentTag = tag ?: run {
            responseHex = "No hay tarjeta"
            return
        }
        if (isSending) return
        val commands = listOf(
            "00 A4 00 00 02 3F 00",
            "00 B0 00 00 10",
            "00 B0 00 00 20",
            "00 B0 00 00 40",
            "80 CA 00 00 00",
            "00 A4 02 00 02 3F 00"
        )
        Thread {
            val results = StringBuilder()
            for (cmd in commands) {
                try {
                    val isoDep = IsoDep.get(currentTag)
                    isoDep?.connect()
                    isoDep?.timeout = 3000
                    val resp = isoDep?.transceive(cmd.hexToByteArray())
                    if (resp != null) {
                        results.append("\n--- $cmd ---\n")
                        results.append("HEX: ${resp.toHexString()}\n")
                        results.append("ASCII: ${resp.toPrintableText()}\n")
                        results.append("Status: ${resp.statusDescription()}\n")
                    } else {
                        results.append("\n--- $cmd --- sin respuesta\n")
                    }
                    isoDep?.close()
                    Thread.sleep(200)
                } catch (e: Exception) {
                    results.append("\n--- $cmd --- ERROR: ${e.message}\n")
                }
            }
            (context as? ComponentActivity)?.runOnUiThread {
                responseHex = results.toString()
                responseText = ""
                statusText = "Autoexploración completada"
            }
        }.start()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Consola APDU", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Acerca la tarjeta NFC al teléfono. Cuando se detecte, podrás enviar comandos.", style = MaterialTheme.typography.bodyMedium)

        OutlinedTextField(
            value = commandHex,
            onValueChange = { commandHex = it },
            label = { Text("Comando APDU en HEX") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 4
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { commandHex = "00 A4 00 00 02 3F 00" }, modifier = Modifier.weight(1f)) { Text("Select MF") }
            Button(onClick = { commandHex = "00 B0 00 00 10" }, modifier = Modifier.weight(1f)) { Text("Read 16") }
            Button(onClick = { commandHex = "00 B0 00 00 20" }, modifier = Modifier.weight(1f)) { Text("Read 32") }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { commandHex = "80 CA 00 00 00" }, modifier = Modifier.weight(1f)) { Text("Get Data") }
            Button(onClick = { commandHex = "00 A4 04 00 07 D2 76 00 00 85 01 01 00" }, modifier = Modifier.weight(1f)) { Text("Select NDEF") }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { sendApdu() }, enabled = !isSending, modifier = Modifier.weight(1f)) { Text(if (isSending) "Enviando..." else "Enviar APDU") }
            Button(onClick = { autoExplore() }, enabled = !isSending, modifier = Modifier.weight(1f)) { Text("Autoexplorar") }
            Button(onClick = { onRefreshTag(); tag = null; responseHex = "Esperando nueva tarjeta..." }, modifier = Modifier.weight(1f)) { Text("Renovar tag") }
        }

        if (responseHex.isNotBlank()) {
            Card(shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Respuesta", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(responseHex, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    if (statusText.isNotBlank()) {
                        Text("Estado", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(statusText, fontSize = 12.sp)
                    }
                    if (responseText.isNotBlank() && responseText != "ASCII: ") {
                        Text("ASCII", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(responseText, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Volver al lector") }
    }
}

// Extensiones necesarias
private fun String.hexToByteArray(): ByteArray {
    val normalized = replace(" ", "").replace("\n", "").replace("\t", "")
    require(normalized.isNotBlank()) { "HEX vacío" }
    require(normalized.length % 2 == 0) { "HEX debe tener longitud par" }
    return normalized.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

private fun ByteArray.toHexString(): String = joinToString(" ") { "%02X".format(Locale.US, it) }

private fun ByteArray.toPrintableText(): String = map { b ->
    val v = b.toInt() and 0xFF
    if (v in 32..126) v.toChar() else '.'
}.joinToString("")

private fun ByteArray.statusDescription(): String {
    if (size < 2) return ""
    val sw1 = this[size - 2].toInt() and 0xFF
    val sw2 = this[size - 1].toInt() and 0xFF
    return when {
        sw1 == 0x90 && sw2 == 0x00 -> "✅ OK"
        sw1 == 0x6A && sw2 == 0x82 -> "❌ Archivo o aplicación no encontrada"
        else -> "SW1 SW2: ${"%02X".format(sw1)} ${"%02X".format(sw2)}"
    }
}

@Composable
fun NfcTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}