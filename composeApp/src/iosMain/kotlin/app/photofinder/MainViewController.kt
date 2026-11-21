package app.photofinder

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** Entry point for the iOS app (Xcode project to be added in iosApp/). */
@Suppress("FunctionName", "ktlint:standard:function-naming") // iOS factory, named like the Swift type it returns
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
