package com.example.nfc

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

class ApduConsoleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tag = (application as MyApplication).currentTag

        if (tag == null || IsoDep.get(tag) == null) {
            Toast.makeText(this, "No hay una tarjeta ISO-DEP activa.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setContent {
            NfcTheme {
                ApduConsoleScreen(tag = tag, onBack = { finish() })
            }
        }
    }
}

@Composable
fun ApduConsoleScreen(tag: Tag, onBack: () -> Unit) {
    val activity = LocalContext.current as ComponentActivity
    var commandHex by remember { mutableStateOf("00 A4 04 00 07 D2 76 00 00 85 01 01 00") }
    var responseHex by remember { mutableStateOf("") }
    var responseText by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }

    fun sendApdu() {
        if (isSending) return
        isSending = true
        responseHex = ""
        responseText = ""
        statusText = ""

        val commandBytes = try {
            commandHex.hexToByteArray()
        } catch (error: Exception) {
            responseHex = "Error: comando HEX invalido"
            statusText = error.message.orEmpty()
            isSending = false
            return
        }

        Thread {
            var isoDep: IsoDep? = null
            try {
                isoDep = IsoDep.get(tag)
                isoDep?.connect()
                isoDep?.timeout = 2000
                val response = isoDep?.transceive(commandBytes)

                activity.runOnUiThread {
                    if (response == null) {
                        responseHex = "Respuesta nula"
                    } else {
                        responseHex = response.toHexString()
                        responseText = response.toPrintableText()
                        statusText = response.statusDescription()
                    }
                }
            } catch (error: Exception) {
                activity.runOnUiThread {
                    responseHex = "Error: ${error.message.orEmpty()}"
                    statusText = "Mantén la tarjeta pegada al celular mientras envias el comando."
                }
            } finally {
                runCatching { isoDep?.close() }
                activity.runOnUiThread { isSending = false }
            }
        }.start()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Consola APDU",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Envia comandos APDU conocidos para diagnosticar tarjetas ISO-DEP.",
            style = MaterialTheme.typography.bodyMedium
        )

        OutlinedTextField(
            value = commandHex,
            onValueChange = { commandHex = it },
            label = { Text("Comando APDU en HEX") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 4,
            placeholder = { Text("Ej: 00 A4 04 00 07 D2 76 00 00 85 01 01 00") }
        )

        Button(
            onClick = ::sendApdu,
            enabled = !isSending,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isSending) "Enviando..." else "Enviar APDU")
        }

        if (responseHex.isNotBlank()) {
            ResponseCard(
                responseHex = responseHex,
                responseText = responseText,
                statusText = statusText
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Volver al lector")
        }
    }
}

@Composable
private fun ResponseCard(responseHex: String, responseText: String, statusText: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Respuesta HEX", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(responseHex, fontSize = 12.sp, fontFamily = FontFamily.Monospace)

            if (statusText.isNotBlank()) {
                Text("Estado", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(statusText, fontSize = 12.sp)
            }

            if (responseText.isNotBlank()) {
                Text("ASCII", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(responseText, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

private fun String.hexToByteArray(): ByteArray {
    val normalized = replace(" ", "").replace("\n", "").replace("\t", "")
    require(normalized.isNotBlank()) { "Escribe un comando APDU." }
    require(normalized.length % 2 == 0) { "El HEX debe tener pares completos." }
    return normalized.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

private fun ByteArray.toHexString(): String =
    joinToString(separator = " ") { byte -> "%02X".format(Locale.US, byte) }

private fun ByteArray.toPrintableText(): String =
    map { byte ->
        val value = byte.toInt() and 0xFF
        if (value in 32..126) value.toChar() else '.'
    }.joinToString(separator = "")

private fun ByteArray.statusDescription(): String {
    if (size < 2) return ""
    val sw1 = this[size - 2].toInt() and 0xFF
    val sw2 = this[size - 1].toInt() and 0xFF
    val status = "%02X %02X".format(Locale.US, sw1, sw2)

    val meaning = when {
        sw1 == 0x90 && sw2 == 0x00 -> "OK"
        sw1 == 0x6A && sw2 == 0x82 -> "Archivo o aplicacion no encontrada"
        sw1 == 0x69 && sw2 == 0x82 -> "Seguridad no satisfecha"
        sw1 == 0x69 && sw2 == 0x85 -> "Condiciones de uso no satisfechas"
        sw1 == 0x67 && sw2 == 0x00 -> "Longitud incorrecta"
        sw1 == 0x6D && sw2 == 0x00 -> "Instruccion no soportada"
        sw1 == 0x6E && sw2 == 0x00 -> "Clase no soportada"
        sw1 == 0x61 -> "Hay mas datos disponibles: $sw2 bytes"
        sw1 == 0x6C -> "Longitud esperada: $sw2 bytes"
        else -> "Estado no reconocido"
    }

    return "SW1 SW2: $status - $meaning"
}
