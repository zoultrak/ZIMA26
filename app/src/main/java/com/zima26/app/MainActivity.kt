package com.zima26.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.zima26.app.ui.AppRoot
import com.zima26.app.ui.theme.Zima26Theme
import com.zima26.app.viewmodel.MainViewModel
import com.zima26.app.viewmodel.Screen

/**
 * Única actividad de la app. Toda la interfaz se dibuja con Compose.
 * También recibe las fotos que se comparten con ZIMA26 desde otras apps.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Zima26Theme {
                AppRoot(viewModel)
            }
        }
        // Solo la primera vez (no al girar ni al recrear la actividad)
        if (savedInstanceState == null) handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** "Compartir -> ZIMA26": una foto se abre en el resultado; varias se revelan en lote. */
    private fun handleShare(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> intent.streamUri()?.let { viewModel.onPhotoImported(it) }
            Intent.ACTION_SEND_MULTIPLE -> intent.streamUris().takeIf { it.isNotEmpty() }?.let { viewModel.developBatch(it) }
        }
    }

    /** Los botones de volumen disparan la cámara (como en la app de cámara del sistema). */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) &&
            viewModel.ui.value.screen == Screen.CAMERA
        ) {
            if (event?.repeatCount == 0) viewModel.requestShutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun Intent.streamUri(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") getParcelableExtra(Intent.EXTRA_STREAM)

    private fun Intent.streamUris(): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()
}
