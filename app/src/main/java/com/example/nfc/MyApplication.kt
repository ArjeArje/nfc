package com.example.nfc

import android.app.Application
import android.nfc.Tag

class MyApplication : Application() {
    var currentTag: Tag? = null
}
