package com.yourapp.audiobook.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.yourapp.audiobook.AudioBookApplication
import com.yourapp.audiobook.UiModeRouter

/** Результат подготовки входа через Google. */
class GoogleSignInController(
    /** Android TV: вход через аккаунт из настроек устройства, без диалога выбора. */
    val isTv: Boolean,
    /** Доступен ли вход на этом устройстве. */
    val available: Boolean,
    /** Начать вход через Google. */
    val onSignInClick: () -> Unit,
)

/**
 * Единая точка входа через Google: на сенсорных экранах открывается выбор
 * аккаунта GoogleSignIn, на Android TV (где выбор аккаунта не поддерживается)
 * используется аккаунт Google из настроек устройства через GoogleAuthUtil.
 */
@Composable
fun rememberGoogleSignInController(): GoogleSignInController {
    val context = LocalContext.current
    val app = context.applicationContext as AudioBookApplication
    val isTv = remember(context) { UiModeRouter.isTvDevice(context) }
    val signInClient = remember {
        runCatching { app.syncManager.buildSignInClient() }.getOrNull()
    }
    val pendingAuth by app.syncManager.pendingAuth.collectAsStateWithLifecycle()
    val signInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        onSignInResult(app, isTv, result)
    }
    val accountPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && isTv) {
            app.syncManager.signInWithTvAccount()
        }
    }
    LaunchedEffect(pendingAuth) {
        pendingAuth?.let { intent ->
            app.syncManager.consumePendingAuth()
            signInLauncher.launch(intent)
        }
    }
    return GoogleSignInController(
        isTv = isTv,
        available = isTv || signInClient != null,
        onSignInClick = {
            if (isTv) {
                // На Android 15+ GET_ACCOUNTS — runtime-разрешение: без него
                // AccountManager не отдаёт аккаунты устройства.
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.GET_ACCOUNTS,
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    app.syncManager.signInWithTvAccount()
                } else {
                    accountPermissionLauncher.launch(Manifest.permission.GET_ACCOUNTS)
                }
            } else {
                signInClient?.signInIntent?.let { signInLauncher.launch(it) }
            }
        },
    )
}

private fun onSignInResult(
    app: AudioBookApplication,
    isTv: Boolean,
    result: ActivityResult,
) {
    if (isTv) {
        // Окно подтверждения Google закрыто — повторяем вход с аккаунтом устройства.
        if (result.resultCode == Activity.RESULT_OK) {
            app.syncManager.signInWithTvAccount()
        }
        return
    }
    if (result.resultCode != Activity.RESULT_OK) return
    val data = result.data ?: return
    runCatching { GoogleSignIn.getSignedInAccountFromIntent(data).getResult() }
        .onSuccess { account: GoogleSignInAccount -> app.syncManager.onSignInOK(account) }
        .onFailure { app.syncManager.onSignInError(it) }
}