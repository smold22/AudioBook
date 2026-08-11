package com.yourapp.audiobook.data.sync

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.yourapp.audiobook.data.BookmarksStore
import com.yourapp.audiobook.data.FavoritesStore
import com.yourapp.audiobook.data.HistoryStore
import com.yourapp.audiobook.data.ProgressStore
import com.yourapp.audiobook.data.SettingsStore
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SyncState(
    val signedIn: Boolean = false,
    val accountEmail: String? = null,
    val syncing: Boolean = false,
    val lastSyncAtMs: Long? = null,
    val lastError: String? = null,
)

/**
 * Синхронизация состояния приложения (избранное, история, прогресс, закладки,
 * настройки) через Firebase Authentication (вход через Google) и Cloud Firestore.
 */
class SyncManager(
    private val context: Context,
    private val favoritesStore: FavoritesStore,
    private val historyStore: HistoryStore,
    private val progressStore: ProgressStore,
    private val bookmarksStore: BookmarksStore,
    private val settingsStore: SettingsStore,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val gson: Gson = GsonBuilder().create()
    private val firebaseAuth = FirebaseAuth.getInstance()
    private val store = FirebaseSyncStore(gson)

    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    fun start() {
        currentUser()?.let {
            _state.update { s ->
                s.copy(signedIn = true, accountEmail = it.email ?: s.accountEmail)
            }
        }
        scope.launch {
            delay(INITIAL_SYNC_DELAY_MS)
            if (currentUser() != null) syncNow()
        }
        scope.launch {
            merge(
                favoritesStore.changes,
                historyStore.changes,
                progressStore.changes,
                bookmarksStore.changes,
                settingsStore.changes,
            )
                .debounce(AUTO_SYNC_DEBOUNCE_MS)
                .collect { syncNow() }
        }
    }

    fun buildSignInClient(): GoogleSignInClient? {
        val clientId = runCatching {
            val resId = context.resources.getIdentifier(
                "default_web_client_id",
                "string",
                context.packageName,
            )
            if (resId != 0) context.getString(resId) else null
        }.getOrNull()
        return runCatching {
            val builder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestScopes(Scope(DRIVE_FILE_SCOPE))
                .requestEmail()
            if (!clientId.isNullOrBlank()) builder.requestIdToken(clientId)
            GoogleSignIn.getClient(context, builder.build())
        }.getOrNull()
    }

    fun onSignInOK(account: GoogleSignInAccount) {
        val idToken = account.idToken
        if (idToken == null) {
            _state.update {
                it.copy(lastError = "Не удалось получить токен Google — в google-services.json нет web-клиента (oauth_client)")
            }
            return
        }
        scope.launch {
            runCatching {
                firebaseAuth
                    .signInWithCredential(GoogleAuthProvider.getCredential(idToken, null))
                    .awaitTask()
            }.onSuccess {
                _state.update { s ->
                    s.copy(
                        signedIn = true,
                        accountEmail = account.email ?: s.accountEmail,
                        lastError = null,
                    )
                }
                doSync()
            }.onFailure {
                _state.update { s -> s.copy(lastError = "Ошибка входа: ${it.message}") }
            }
        }
    }

    fun onSignInError(throwable: Throwable) {
        val message = if (throwable is ApiException &&
            throwable.statusCode == GoogleSignInStatusCodes.SIGN_IN_CANCELLED
        ) {
            "Вход отменён"
        } else {
            "Ошибка входа: ${throwable.message}"
        }
        _state.update { s -> s.copy(lastError = message) }
    }

    fun signOut() {
        scope.launch {
            runCatching { firebaseAuth.signOut() }
            runCatching {
                GoogleSignIn.getClient(context, GoogleSignInOptions.DEFAULT_SIGN_IN).signOut()
            }
            _state.value = SyncState()
        }
    }

    fun syncNow() {
        if (_state.value.syncing) return
        scope.launch { doSync() }
    }

    private suspend fun doSync() = mutex.withLock {
        if (_state.value.syncing || currentUser() == null) return@withLock
        _state.update { it.copy(syncing = true, lastError = null) }
        try {
            val local = withContext(Dispatchers.IO) {
                SyncData(
                    updatedAtMs = System.currentTimeMillis(),
                    favorites = favoritesStore.snapshot(),
                    history = historyStore.snapshot(),
                    progress = progressStore.snapshot(),
                    bookmarks = bookmarksStore.snapshot(),
                    settings = settingsStore.snapshotAll(),
                )
            }

            val remote = store.read(currentUser()!!.uid)

            val merged = remote?.mergedWith(local) ?: local

            applyLocal(merged)
            store.write(currentUser()!!.uid, merged)
            _state.update {
                it.copy(lastSyncAtMs = System.currentTimeMillis(), lastError = null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { s -> s.copy(lastError = "Ошибка синхронизации: ${e.message}") }
        } finally {
            _state.update { it.copy(syncing = false) }
        }
    }

    private suspend fun applyLocal(data: SyncData) {
        favoritesStore.restore(data.favorites)
        historyStore.restore(data.history.take(MAX_HISTORY_ENTRIES))
        progressStore.restore(data.progress)
        bookmarksStore.restore(data.bookmarks)
        settingsStore.restoreAll(data.settings)
    }

    fun currentUser(): FirebaseUser? = firebaseAuth.currentUser

    companion object {
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val AUTO_SYNC_DEBOUNCE_MS = 20_000L
        private const val INITIAL_SYNC_DELAY_MS = 8_000L
        private const val MAX_HISTORY_ENTRIES = 100
    }
}
