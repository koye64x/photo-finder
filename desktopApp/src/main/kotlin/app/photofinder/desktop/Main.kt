package app.photofinder.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.photofinder.App

fun main() =
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Фото по лицу",
            // Tablet-like landscape size
            state = rememberWindowState(width = 1280.dp, height = 800.dp),
        ) {
            App()
        }
    }
