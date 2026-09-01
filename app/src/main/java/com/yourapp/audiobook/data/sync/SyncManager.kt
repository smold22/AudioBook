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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SyncState(
    val signedIn: Boolean = false,
    val accountEmail: String? = null,
    val syncing: Boolean = false,
    val restoring: Boolean = false,
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
    private val syncStateStore = SyncStateStore(context)

    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val _backups = MutableStateFlow<List<BackupInfo>>(emptyList())
    val backups: StateFlow<List<BackupInfo>> = _backups.asStateFlow()

    private var started = false
    private var reSyncQueued = false

    fun start() {
        if (started) return
        started = true
        currentUser()?.let {
            _state.update { s ->
                s.copy(signedIn = true, accountEmail = it.email ?: s.accountEmail)
            }
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
            _backups.value = emptyList()
        }
    }

    fun syncNow() {
        if (_state.value.syncing || _state.value.restoring) {
            reSyncQueued = true
            return
        }
        scope.launch { doSync() }
    }

    /** Обновляет список доступных резервных копий. */
    fun refreshBackups() {
        currentUser()?.let { user ->
            scope.launch { refreshBackups(user.uid) }
        }
    }

    /** Восстанавливает выбранную резервную копию из облака. */
    fun restoreBackup(backupId: String) {
        if (_state.value.syncing || _state.value.restoring) return
        scope.launch { doRestore(backupId) }
    }

    private suspend fun refreshBackups(uid: String) {
        runCatching { store.listBackups(uid) }
            .onSuccess { _backups.value = it }
    }

    private suspend fun doRestore(backupId: String) = mutex.withLock {
        val user = currentUser() ?: return@withLock
        if (_state.value.syncing || _state.value.restoring) return@withLock
        _state.update { it.copy(restoring = true, lastError = null) }
        try {
            val data = store.readBackup(user.uid, backupId)
                ?: throw IllegalStateException("Резервная копия не найдена")
            applyLocal(data)
            store.write(user.uid, data.copy(updatedAtMs = System.currentTimeMillis()))
            _state.update {
                it.copy(lastSyncAtMs = System.currentTimeMillis(), lastError = null)
            }
            _backups.value = store.listBackups(user.uid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { s -> s.copy(lastError = "Ошибка восстановления: ${e.message}") }
        } finally {
            _state.update { it.copy(restoring = false) }
        }
    }

    private suspend fun doSync() = mutex.withLock {
        val user = currentUser() ?: return@withLock
        if (_state.value.syncing || _state.value.restoring) return@withLock
        _state.update { it.copy(syncing = true, lastError = null) }
        try {
            val uid = user.uid
            val localChangedAt = syncStateStore.lastChangeMs()
            val local = snapshotLocal()

            val remote = store.read(uid)

            // Пользователь мог выйти из аккаунта во время сетевого обмена — не пишем чужие данные.
            if (currentUser()?.uid != uid) return@withLock

            // Побеждают данные той стороны, которая менялась позже (last-write-wins).
            // При неизвестных метках времени (0) — объединяем, сохраняя записи обеих сторон.
            // Пустой новый аккаунт забирает данные из облака, старый — не теряет локальное.
            val merged: SyncData = when {
                remote == null -> local
                localChangedAt > 0L && remote.updatedAtMs > 0L && localChangedAt >= remote.updatedAtMs -> local
                !hasLocalData(local) && localChangedAt == 0L -> remote
                else -> remote.unionWith(local)
            }

            if (merged !== local) {
                // Перечитываем текущее локальное состояние и объединяем с результатом,
                // чтобы не затереть правки, сделанные во время сетевого обмена.
                val currentLocal = snapshotLocal()
                applyLocal(currentLocal.unionWith(merged))
            }
            store.write(uid, merged)
            _state.update {
                it.copy(lastSyncAtMs = System.currentTimeMillis(), lastError = null)
            }
            _backups.value = store.listBackups(uid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { s -> s.copy(lastError = "Ошибка синхронизации: ${e.message}") }
        } finally {
            _state.update { it.copy(syncing = false) }
            if (reSyncQueued && !_state.value.restoring) {
                reSyncQueued = false
                syncNow()
            }
        }
    }

    private suspend fun snapshotLocal(): SyncData = withContext(Dispatchers.IO) {
        SyncData(
            updatedAtMs = syncStateStore.lastChangeMs(),
            favorites = favoritesStore.snapshot(),
            history = historyStore.snapshot(),
            progress = progressStore.snapshot(),
            bookmarks = bookmarksStore.snapshot(),
            settings = settingsStore.snapshotAll(),
        )
    }

    private fun hasLocalData(data: SyncData): Boolean =
        data.favorites.isNotEmpty() ||
            data.history.isNotEmpty() ||
            data.progress.isNotEmpty() ||
            data.bookmarks.isNotEmpty()

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
        private const val MAX_HISTORY_ENTRIES = 100
    }
}
