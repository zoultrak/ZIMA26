package com.zima26.app

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader

/**
 * Clase Application: inicializa OpenCV una sola vez al arrancar el proceso.
 */
class Zima26App : Application() {

    override fun onCreate() {
        super.onCreate()
        // Carga las librerías nativas de OpenCV incluidas en el APK.
        val ok = OpenCVLoader.initLocal()
        Log.i(TAG, "OpenCV inicializado: $ok")
    }

    companion object {
        private const val TAG = "Zima26App"
    }
}
