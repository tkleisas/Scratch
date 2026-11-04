package com.tkleisas.scratchdj

import android.app.Application

class ScratchApplication : Application() {
    lateinit var controller: DjController
        private set

    override fun onCreate() {
        super.onCreate()
        controller = DjController(this)
    }
}
