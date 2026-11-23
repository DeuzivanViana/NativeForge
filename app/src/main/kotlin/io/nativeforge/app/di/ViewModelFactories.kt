package io.nativeforge.app.di

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import io.nativeforge.app.NativeForgeApplication

/** Access to the [AppContainer] from composables. */
@Composable
fun appContainer(): AppContainer =
    (LocalContext.current.applicationContext as NativeForgeApplication).container
