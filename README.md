# nfc

Aplicacion Android gratuita para leer tarjetas NFC desde un celular compatible, hecha con Kotlin y Jetpack Compose.

## Funciones

- Lee UID/codigo de la tarjeta.
- Detecta tecnologias NFC como IsoDep, NfcA, NfcB, Ndef, MifareClassic y MifareUltralight.
- Muestra contenido NDEF publico cuando existe.
- Muestra datos tecnicos en HEX.
- Incluye consola APDU para diagnosticar tarjetas ISO-DEP con comandos conocidos.

## Requisitos

- Android Studio.
- Celular fisico con NFC activado.
- Android SDK instalado.

## Nota

La app no rompe cifrado ni extrae llaves privadas. Solo muestra datos publicos o respuestas permitidas por la tarjeta.
