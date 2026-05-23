package com.example.nfc

import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NfcA
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.nio.charset.Charset
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var nfcAdapter: NfcAdapter? = null
    private var uiState by mutableStateOf(NfcUiState())
    private var currentTag: Tag? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        uiState = uiState.copy(
            hasNfc = packageManager.hasSystemFeature(PackageManager.FEATURE_NFC),
            isNfcEnabled = nfcAdapter?.isEnabled == true
        )
        handleNfcIntent(intent)

        setContent {
            NfcTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF8FAFC)) {
                    NfcReaderScreen(
                        state = uiState,
                        onOpenApduConsole = {
                            val tag = currentTag
                            if (tag == null || "IsoDep" !in uiState.technologies) {
                                Toast.makeText(
                                    this,
                                    "Primero acerca una tarjeta ISO-DEP al celular.",
                                    Toast.LENGTH_LONG
                                ).show()
                                return@NfcReaderScreen
                            }

                            (application as MyApplication).currentTag = tag
                            startActivity(Intent(this, ApduConsoleActivity::class.java))
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        uiState = uiState.copy(isNfcEnabled = nfcAdapter?.isEnabled == true)
        enableNfcReader()
    }

    override fun onPause() {
        nfcAdapter?.disableReaderMode(this)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNfcIntent(intent)
    }

    private fun enableNfcReader() {
        val adapter = nfcAdapter ?: return
        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V or
            NfcAdapter.FLAG_READER_NFC_BARCODE

        adapter.enableReaderMode(
            this,
            { tag ->
                currentTag = tag
                runOnUiThread { uiState = tag.readNfcData() }
            },
            flags,
            Bundle()
        )
    }

    private fun handleNfcIntent(intent: Intent?) {
        val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }

        if (tag != null) {
            currentTag = tag
            uiState = tag.readNfcData()
            return
        }

        val records = intent?.ndefMessages().orEmpty().flatMap { message ->
            message.records.map(::readNdefRecord)
        }

        if (records.isNotEmpty()) {
            uiState = uiState.copy(
                scanStatus = "Contenido NDEF detectado",
                content = records
            )
        }
    }

    private fun Tag.readNfcData(): NfcUiState {
        val technologies = techList.map { it.substringAfterLast('.') }.sorted()
        val ndefInfo = readNdefInfo(this)
        val rawMemory = readMifareUltralightMemory(this)
        val classicMemory = readMifareClassicMemory(this)
        val isoDepInfo = readIsoDepInfo(this)
        val nfcAInfo = readNfcAInfo(this)
        val canEdit = when {
            ndefInfo.isWritable -> "Si, esta etiqueta NDEF permite escritura"
            ndefInfo.isNdef -> "No, esta etiqueta NDEF esta en solo lectura"
            else -> "No detectado. La tarjeta no expone NDEF editable"
        }

        return NfcUiState(
            hasNfc = packageManager.hasSystemFeature(PackageManager.FEATURE_NFC),
            isNfcEnabled = nfcAdapter?.isEnabled == true,
            scanStatus = "Tarjeta leida correctamente",
            tagId = id.toHexString(),
            tagType = ndefInfo.type.ifBlank { detectTagFamily(technologies) },
            canEdit = canEdit,
            maxSize = ndefInfo.maxSize,
            technologies = technologies,
            content = ndefInfo.records.ifEmpty {
                listOf("No se encontro texto o enlace NDEF publico en esta tarjeta")
            },
            technicalData = nfcAInfo + isoDepInfo + rawMemory + classicMemory
        )
    }
}

data class NfcUiState(
    val hasNfc: Boolean = false,
    val isNfcEnabled: Boolean = false,
    val scanStatus: String = "Acerca la tarjeta NFC a la parte trasera del celular",
    val tagId: String = "",
    val tagType: String = "",
    val canEdit: String = "",
    val maxSize: Int = 0,
    val technologies: List<String> = emptyList(),
    val content: List<String> = emptyList(),
    val technicalData: List<String> = emptyList()
)

private data class NdefInfo(
    val isNdef: Boolean = false,
    val type: String = "",
    val isWritable: Boolean = false,
    val maxSize: Int = 0,
    val records: List<String> = emptyList()
)

private fun readNdefInfo(tag: Tag): NdefInfo {
    val ndef = Ndef.get(tag) ?: return NdefInfo()
    return try {
        ndef.connect()
        val message = ndef.ndefMessage ?: ndef.cachedNdefMessage
        NdefInfo(
            isNdef = true,
            type = ndef.type ?: "NDEF",
            isWritable = ndef.isWritable,
            maxSize = ndef.maxSize,
            records = message?.records?.map(::readNdefRecord).orEmpty()
        )
    } catch (error: Exception) {
        NdefInfo(
            isNdef = true,
            type = ndef.type ?: "NDEF",
            isWritable = ndef.isWritable,
            maxSize = ndef.maxSize,
            records = listOf("No se pudo abrir el contenido NDEF: ${error.message.orEmpty()}")
        )
    } finally {
        runCatching { ndef.close() }
    }
}

private fun readNfcAInfo(tag: Tag): List<String> {
    val nfcA = NfcA.get(tag) ?: return emptyList()
    return listOf(
        "ATQA: ${nfcA.atqa.toHexString()}",
        "SAK: ${"%02X".format(Locale.US, nfcA.sak)}"
    )
}

private fun readIsoDepInfo(tag: Tag): List<String> {
    val isoDep = IsoDep.get(tag) ?: return emptyList()
    val lines = mutableListOf<String>()

    return try {
        isoDep.connect()
        isoDep.timeout = 1500
        lines += "ISO-DEP detectado"
        lines += "Tamano maximo transceive: ${isoDep.maxTransceiveLength} bytes"
        lines += "Timeout APDU: ${isoDep.timeout} ms"
        isoDep.hiLayerResponse?.takeIf { it.isNotEmpty() }?.let {
            lines += "HiLayerResponse: ${it.toHexString()}"
        }
        isoDep.historicalBytes?.takeIf { it.isNotEmpty() }?.let {
            lines += "HistoricalBytes: ${it.toHexString()}"
        }

        lines += trySelectType4Ndef(isoDep)
        lines
    } catch (error: Exception) {
        listOf("ISO-DEP no accesible: ${error.message.orEmpty()}")
    } finally {
        runCatching { isoDep.close() }
    }
}

private fun trySelectType4Ndef(isoDep: IsoDep): List<String> {
    val lines = mutableListOf<String>()
    val selectNdefApplication = hexToBytes("00 A4 04 00 07 D2 76 00 00 85 01 01 00")
    val selectCapabilityContainer = hexToBytes("00 A4 00 0C 02 E1 03")
    val readCapabilityContainer = hexToBytes("00 B0 00 00 0F")

    val selectApp = runCatching { isoDep.transceive(selectNdefApplication) }.getOrNull()
    lines += "APDU SELECT NDEF app: ${selectApp?.toHexString() ?: "sin respuesta"}"

    if (selectApp?.endsWithSuccessStatus() != true) {
        lines += "No expone aplicacion NDEF publica tipo 4"
        lines += "Para leer mas se necesita el AID/protocolo de esa tarjeta"
        return lines
    }

    val selectCc = runCatching { isoDep.transceive(selectCapabilityContainer) }.getOrNull()
    lines += "APDU SELECT CC file: ${selectCc?.toHexString() ?: "sin respuesta"}"
    if (selectCc?.endsWithSuccessStatus() != true) return lines

    val cc = runCatching { isoDep.transceive(readCapabilityContainer) }.getOrNull()
    lines += "APDU READ CC file: ${cc?.toHexString() ?: "sin respuesta"}"
    return lines
}

private fun readMifareUltralightMemory(tag: Tag): List<String> {
    val ultralight = MifareUltralight.get(tag) ?: return emptyList()
    val pages = mutableListOf<String>()

    return try {
        ultralight.connect()
        for (page in 0..12 step 4) {
            val bytes = ultralight.readPages(page)
            pages += "Paginas $page-${page + 3}: ${bytes.toHexString()}"
        }
        pages
    } catch (error: Exception) {
        listOf("Memoria Mifare Ultralight no accesible: ${error.message.orEmpty()}")
    } finally {
        runCatching { ultralight.close() }
    }
}

private fun readMifareClassicMemory(tag: Tag): List<String> {
    val classic = MifareClassic.get(tag) ?: return emptyList()
    val lines = mutableListOf<String>()
    val keys = listOf(
        "KEY_DEFAULT FF FF FF FF FF FF" to MifareClassic.KEY_DEFAULT,
        "KEY_NFC_FORUM D3 F7 D3 F7 D3 F7" to MifareClassic.KEY_NFC_FORUM,
        "KEY_MAD A0 A1 A2 A3 A4 A5" to MifareClassic.KEY_MIFARE_APPLICATION_DIRECTORY
    )

    return try {
        classic.connect()
        lines += "Mifare Classic: ${classic.size} bytes, ${classic.sectorCount} sectores"

        for (sector in 0 until classic.sectorCount) {
            val keyName = keys.firstOrNull { (_, key) ->
                classic.authenticateSectorWithKeyA(sector, key) ||
                    classic.authenticateSectorWithKeyB(sector, key)
            }?.first

            if (keyName == null) {
                lines += "Sector $sector: protegido o requiere llave propia"
                continue
            }

            lines += "Sector $sector: abierto con $keyName"
            val firstBlock = classic.sectorToBlock(sector)
            val blockCount = classic.getBlockCountInSector(sector)
            for (offset in 0 until blockCount) {
                val block = firstBlock + offset
                val data = classic.readBlock(block)
                lines += "Bloque $block: ${data.toHexString()} | ${data.toPrintableText()}"
            }
        }
        lines
    } catch (error: Exception) {
        listOf("Mifare Classic no accesible: ${error.message.orEmpty()}")
    } finally {
        runCatching { classic.close() }
    }
}

private fun detectTagFamily(technologies: List<String>): String {
    return when {
        "MifareUltralight" in technologies -> "Mifare Ultralight / NTAG"
        "MifareClassic" in technologies -> "Mifare Classic"
        "IsoDep" in technologies -> "ISO-DEP / tarjeta inteligente"
        "NfcV" in technologies -> "NFC-V / ISO 15693"
        "NfcF" in technologies -> "NFC-F / FeliCa"
        else -> "Tarjeta NFC"
    }
}

private fun Intent.ndefMessages(): List<NdefMessage> {
    val rawMessages: Array<Parcelable> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, Parcelable::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
    } ?: return emptyList()

    return rawMessages.filterIsInstance<NdefMessage>()
}

private fun readNdefRecord(record: NdefRecord): String {
    return when {
        record.tnf == NdefRecord.TNF_WELL_KNOWN && record.type.contentEquals(NdefRecord.RTD_TEXT) ->
            "Texto: ${record.readTextPayload()}\nPayload HEX: ${record.payload.toHexString()}"
        record.tnf == NdefRecord.TNF_WELL_KNOWN && record.type.contentEquals(NdefRecord.RTD_URI) ->
            "Enlace: ${record.toUri()?.toString().orEmpty()}\nPayload HEX: ${record.payload.toHexString()}"
        else -> "Registro NDEF TNF ${record.tnf}, tipo ${record.type.toHexString()}\nPayload HEX: ${record.payload.toHexString()}"
    }
}

private fun NdefRecord.readTextPayload(): String {
    if (payload.isEmpty()) return ""
    val statusByte = payload[0].toInt()
    val languageCodeLength = statusByte and 0x3F
    val charset = if ((statusByte and 0x80) == 0) Charsets.UTF_8 else Charset.forName("UTF-16")
    return payload.copyOfRange(1 + languageCodeLength, payload.size).toString(charset)
}

private fun ByteArray.toHexString(): String =
    joinToString(separator = " ") { byte -> "%02X".format(Locale.US, byte) }

private fun hexToBytes(hex: String): ByteArray =
    hex.replace(" ", "")
        .chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()

private fun ByteArray.endsWithSuccessStatus(): Boolean =
    size >= 2 && this[size - 2] == 0x90.toByte() && this[size - 1] == 0x00.toByte()

private fun ByteArray.toPrintableText(): String {
    val text = map { byte ->
        val value = byte.toInt() and 0xFF
        if (value in 32..126) value.toChar() else '.'
    }.joinToString(separator = "")

    return "ASCII: $text"
}

@Composable
private fun NfcReaderScreen(state: NfcUiState, onOpenApduConsole: () -> Unit) {
    val statusText = when {
        !state.hasNfc -> "Este celular no tiene NFC"
        !state.isNfcEnabled -> "Activa NFC en ajustes del telefono"
        else -> state.scanStatus
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8FAFC))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "Lector NFC",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0F172A)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFF475569)
            )
        }

        if (state.tagId.isNotBlank()) {
            item {
                InfoCard(
                    title = "Resultado",
                    rows = listOf(
                        "Codigo / UID" to state.tagId,
                        "Tipo" to state.tagType,
                        "Editar" to state.canEdit,
                        "Capacidad NDEF" to if (state.maxSize > 0) "${state.maxSize} bytes" else "No reportada"
                    )
                )
            }
        }

        item {
            SectionTitle("Contenido")
        }
        if (state.content.isEmpty()) {
            item { SmallCard("Todavia no hay lectura. Acerca una tarjeta NFC.") }
        } else {
            items(state.content) { SmallCard(it) }
        }

        if (state.technologies.isNotEmpty()) {
            item { SectionTitle("Tecnologias detectadas") }
            items(state.technologies) { SmallCard(it) }
        }

        if (state.technicalData.isNotEmpty()) {
            item { SectionTitle("Datos tecnicos") }
            items(state.technicalData) { SmallCard(it) }
        }

        item {
            Button(
                onClick = onOpenApduConsole,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F766E))
            ) {
                Text("Consola APDU")
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        modifier = Modifier.padding(top = 4.dp),
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = Color(0xFF0F172A)
    )
}

@Composable
private fun InfoCard(title: String, rows: List<Pair<String, String>>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            rows.forEach { (label, value) ->
                Column {
                    Text(text = label, style = MaterialTheme.typography.labelMedium, color = Color(0xFF64748B))
                    Text(text = value, style = MaterialTheme.typography.bodyLarge, color = Color(0xFF0F172A))
                }
            }
        }
    }
}

@Composable
private fun SmallCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(modifier = Modifier.padding(14.dp)) {
            Text(text = text, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF0F172A))
        }
    }
}

@Composable
fun NfcTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF0F766E),
            secondary = Color(0xFF2563EB),
            tertiary = Color(0xFFE11D48),
            surface = Color.White,
            background = Color(0xFFF8FAFC)
        ),
        content = content
    )
}

@Preview(showBackground = true)
@Composable
private fun PreviewNfcReaderScreen() {
    NfcTheme {
        NfcReaderScreen(
            state = NfcUiState(
                hasNfc = true,
                isNfcEnabled = true,
                scanStatus = "Tarjeta leida correctamente",
                tagId = "04 A2 19 6B 83 10 90",
                tagType = "NFC Forum Type 2",
                canEdit = "Si, esta etiqueta NDEF permite escritura",
                maxSize = 512,
                technologies = listOf("Ndef", "NfcA", "MifareUltralight"),
                content = listOf("Texto: Hola NFC", "Enlace: https://developer.android.com"),
                technicalData = listOf("ATQA: 44 00", "SAK: 00", "Paginas 0-3: 04 A2 19 6B 83 10 90 00 00 00 00 00 00 00 00 00")
            ),
            onOpenApduConsole = {}
        )
    }
}
