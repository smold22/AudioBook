package com.yourapp.audiobook.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** GitHub-репозиторий, из релизов которого берутся обновления. */
private const val GITHUB_OWNER = "smold22"
private const val GITHUB_REPO = "AudioBook"

/** Не чаще одного раза в 6 часов при автоматической проверке. */
private const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

/** Информация о доступном обновлении из GitHub Releases. */
data class UpdateInfo(
    val versionName: String,
    val versionCode: Long?,
    val changelog: String,
    val apkUrl: String,
    val apkSize: Long,
    val releaseUrl: String,
)

/** Состояние проверки/загрузки обновления. */
sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object UpToDate : UpdateStatus
    data class Available(val info: UpdateInfo) : UpdateStatus

    /** [percent] == -1, если размер ответа неизвестен. */
    data class Downloading(val percent: Int) : UpdateStatus
    data class Ready(val file: File) : UpdateStatus
    data class Failed(val message: String) : UpdateStatus
}

/**
 * Проверка обновлений через GitHub Releases API, загрузка APK
 * и запуск системного установщика через FileProvider.
 */
class UpdateManager(private val appContext: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = appContext.getSharedPreferences("update", Context.MODE_PRIVATE)

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    /** Версия, для которой нужно показать диалог «доступно обновление». */
    private val _promptVersion = MutableStateFlow<String?>(null)
    val promptVersion: StateFlow<String?> = _promptVersion.asStateFlow()

    private val _installPrompt = MutableStateFlow(false)
    val installPrompt: StateFlow<Boolean> = _installPrompt.asStateFlow()

    private var downloadJob: Job? = null

    private val installedVersionCode: Long by lazy {
        runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).longVersionCode
        }.getOrDefault(0L)
    }

    private val installedVersionName: String by lazy {
        runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName.orEmpty()
        }.getOrDefault("0")
    }

    private fun apkFile(): File {
        val dir = File(appContext.cacheDir, "apk")
        dir.mkdirs()
        return File(dir, "update.apk")
    }

    /**
     * Проверяет последний релиз на GitHub. При [auto] пропускает повторную
     * проверку в течение [AUTO_CHECK_INTERVAL_MS] и не показывает диалог
     * для уже показанной версии.
     */
    fun checkForUpdates(auto: Boolean) {
        if (_status.value == UpdateStatus.Checking) return
        if (auto && downloadJob?.isActive == true) return
        if (auto && System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0L) < AUTO_CHECK_INTERVAL_MS) return
        _status.value = UpdateStatus.Checking
        scope.launch {
            try {
                val info = fetchLatestRelease()
                prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
                if (info == null) {
                    _status.value = UpdateStatus.UpToDate
                    return@launch
                }
                _status.value = UpdateStatus.Available(info)
                val notified = prefs.getString(KEY_NOTIFIED_VERSION, null)
                if (!auto || info.versionName != notified) {
                    _promptVersion.value = info.versionName
                }
            } catch (e: Exception) {
                _status.value = UpdateStatus.Failed(friendlyError(e))
            }
        }
    }

    /** Прячет диалог обновления и запоминает версию, чтобы не показывать его снова. */
    fun dismissPrompt() {
        val info = (_status.value as? UpdateStatus.Available)?.info
        if (info != null) {
            prefs.edit().putString(KEY_NOTIFIED_VERSION, info.versionName).apply()
        }
        _promptVersion.value = null
    }

    /** Скачивает APK из релиза с прогрессом в [status]. */
    fun download(info: UpdateInfo) {
        if (downloadJob?.isActive == true) return
        val target = apkFile()
        target.delete()
        downloadJob = scope.launch {
            try {
                val request = Request.Builder().url(info.apkUrl).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code}")
                    }
                    val body = response.body ?: throw IOException("Пустой ответ сервера")
                    val total = body.contentLength()
                    val input = body.byteStream()
                    FileOutputStream(target).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var lastPercent = -2
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            downloaded += read
                            val percent = if (total > 0) {
                                ((downloaded * 100) / total).toInt()
                            } else {
                                -1
                            }
                            if (percent != lastPercent) {
                                lastPercent = percent
                                _status.value = UpdateStatus.Downloading(percent)
                            }
                        }
                    }
                }
                if (!target.exists() || target.length() == 0L) {
                    throw IOException("Файл обновления пуст")
                }
                _status.value = UpdateStatus.Ready(target)
            } catch (e: Exception) {
                target.delete()
                _status.value = UpdateStatus.Failed(friendlyError(e))
            }
        }
    }

    /** Повторно запрашивает диалог установки уже скачанного обновления. */
    fun requestInstallPrompt() {
        if (_status.value is UpdateStatus.Ready) _installPrompt.value = true
    }

    fun dismissInstallPrompt() {
        _installPrompt.value = false
    }

    /** Запускает системный установщик для скачанного APK. */
    fun install(file: File) {
        if (!file.exists()) {
            _status.value = UpdateStatus.Failed("Файл обновления не найден")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !appContext.packageManager.canRequestPackageInstalls()
        ) {
            runCatching {
                appContext.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:${appContext.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return
        }
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { appContext.startActivity(intent) }
            .onFailure { _status.value = UpdateStatus.Failed("Не удалось открыть установщик: ${it.message}") }
    }

    private fun fetchLatestRelease(): UpdateInfo? {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            val json = JsonParser.parseString(response.body?.string().orEmpty()).asJsonObject
            val tag = json["tag_name"]?.asString.orEmpty()
            if (json["draft"]?.asBoolean == true || json["prerelease"]?.asBoolean == true) return null
            if (!isNewer(tag)) return null
            val assets = json["assets"]?.asJsonArray
            val apk = assets?.mapNotNull { it.asJsonObject }
                ?.firstOrNull { it["name"]?.asString.orEmpty().endsWith(".apk", ignoreCase = true) }
            val apkUrl = apk?.get("browser_download_url")?.asString
            if (apkUrl.isNullOrEmpty()) return null
            return UpdateInfo(
                versionName = parseVersionName(tag),
                versionCode = parseVersionCode(tag),
                changelog = json["body"]?.asString.orEmpty().trim(),
                apkUrl = apkUrl,
                apkSize = apk["size"]?.asLong ?: 0L,
                releaseUrl = json["html_url"]?.asString.orEmpty(),
            )
        }
    }

    private fun isNewer(tag: String): Boolean {
        val remoteCode = parseVersionCode(tag)
        if (remoteCode != null) return remoteCode > installedVersionCode
        return compareVersionNames(parseVersionName(tag), installedVersionName) > 0
    }

    private fun friendlyError(e: Throwable): String = when {
        e is IOException && e.message?.contains("HTTP 403") == true ->
            "Превышен лимит запросов к GitHub. Повторите позже."
        e is IOException && e.message?.contains("HTTP 404") == true ->
            "Релиз не найден в репозитории GitHub."
        e is IOException ->
            "Ошибка сети: ${e.message ?: "нет соединения"}"
        else ->
            "Ошибка: ${e.message ?: "неизвестная ошибка"}"
    }

    private companion object {
        const val KEY_LAST_CHECK = "last_check"
        const val KEY_NOTIFIED_VERSION = "notified_version"

        private val versionCodeRegex = Regex("""\((\d+)\)""")

        /** Извлекает код версии из тега вида "v1.3.8 (3)". */
        fun parseVersionCode(tag: String): Long? =
            versionCodeRegex.find(tag)?.groupValues?.get(1)?.toLongOrNull()

        /** Извлекает номер версии из тега: "v1.3.8" -> "1.3.8". */
        fun parseVersionName(tag: String): String =
            Regex("""\d+(\.\d+)*""").find(tag)?.value.orEmpty()

        fun compareVersionNames(a: String, b: String): Int {
            val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
            val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val x = pa.getOrElse(i) { 0 }
                val y = pb.getOrElse(i) { 0 }
                if (x != y) return x.compareTo(y)
            }
            return 0
        }
    }
}
