package com.zima26.app.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zima26.app.ui.album.AlbumScreen
import com.zima26.app.ui.camera.CameraScreen
import com.zima26.app.ui.components.DevelopingOverlay
import com.zima26.app.ui.result.ResultScreen
import com.zima26.app.ui.settings.SettingsScreen
import com.zima26.app.viewmodel.MainViewModel
import com.zima26.app.viewmodel.Screen
import com.zima26.app.viewmodel.UiEvent

/**
 * Raíz de la interfaz: muestra la pantalla actual, el indicador "Revelando..." y
 * atiende los eventos de un solo uso (mensajes y compartir).
 */
@Composable
fun AppRoot(viewModel: MainViewModel) {
    val context = LocalContext.current
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is UiEvent.Message -> Toast.makeText(context, event.text, Toast.LENGTH_SHORT).show()
                is UiEvent.Share -> context.startActivity(event.intent)
            }
        }
    }

    BackHandler(enabled = ui.screen != Screen.CAMERA) { viewModel.back() }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = ui.screen,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "pantalla"
        ) { screen ->
            when (screen) {
                Screen.CAMERA -> CameraScreen(viewModel)
                Screen.RESULT -> ResultScreen(viewModel)
                Screen.SETTINGS -> SettingsScreen(viewModel)
                Screen.ALBUM -> AlbumScreen(viewModel)
            }
        }
        if (ui.processing) DevelopingOverlay(ui.progress, ui.batchLabel)
    }
}
