@file:Suppress("MaxLineLength", "NestedBlockDepth", "ThrowsCount") // each verification step fails with its own reason

package dev.benfranklin.tether.v5

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import dev.benfranklin.tether.BuildConfig
import dev.benfranklin.tether.core.features.GitHubReleases
import dev.benfranklin.tether.core.features.UpdateFeed
import dev.benfranklin.tether.core.features.UpdateInfo
import dev.benfranklin.tether.core.features.Versions
import dev.benfranklin.tether.core.util.Hex
import dev.benfranklin.tether.log.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-app updates. Default source: the private Benfranklinms/Tether GitHub releases, read with a
 * fine-grained read-only token kept in [TokenStore] (Keystore-encrypted). Optional alternative: a
 * custom public https feed (tether-update.json). Downloads the APK over HTTPS, verifies its SHA-256 (when the
 * source provides one), its package name, that it is newer, and that it is signed with the
 * same certificate as the installed app, then hands it to Android's package installer
 * (REQUEST_INSTALL_PACKAGES; Android asks the user to confirm).
 */
class Updater(private val context: Context) {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val checkedVersion: String) : State
        data class Available(val info: UpdateInfo) : State
        data class Downloading(val info: UpdateInfo, val progress: Float) : State
        data class Installing(val info: UpdateInfo) : State
        data class Failed(val reason: String) : State
        /** No token saved and no custom feed: "Add a GitHub token to check for updates". */
        data object NeedsToken : State
    }

    private val state = MutableStateFlow<State>(State.Idle)
    val status: StateFlow<State> = state.asStateFlow()

    /** The source: a custom https feed when set, else the private releases API. */
    fun sources(custom: String?): List<String> =
        listOf(custom?.trim()?.takeIf { it.startsWith("https://") } ?: GitHubReleases.LATEST_URL)

    /** Token for api.github.com requests (never with a custom feed). */
    private fun token(custom: String?): String? =
        if (custom?.trim()?.startsWith("https://") == true) null else TokenStore.read(context)

    fun check(custom: String?, done: ((State) -> Unit)? = null) {
        if (state.value is State.Checking || state.value is State.Downloading) return
        state.value = State.Checking
        Thread({
            val result = runCheck(custom)
            state.value = result
            Log.i("update.check", "result" to result::class.simpleName)
            done?.invoke(result)
        }, "tether-update").start()
    }

    private fun runCheck(custom: String?): State {
        val customFeed = custom?.trim()?.startsWith("https://") == true
        val token = token(custom)
        if (!customFeed && token == null) return State.NeedsToken
        val errors = ArrayList<String>()
        for (src in sources(custom)) {
            try {
                val text = get(src, token)
                val info = UpdateFeed.parse(text) ?: run {
                    errors += "${host(src)}: not an update feed"
                    null
                } ?: continue
                return if (Versions.newer(info.version, BuildConfig.VERSION_NAME) && info.android != null) {
                    State.Available(info)
                } else {
                    State.UpToDate(info.version)
                }
            } catch (e: IOException) {
                errors += "${host(src)}: ${e.message ?: "unreachable"}"
            }
        }
        return State.Failed(errors.joinToString("; ").take(MAX_REASON).ifEmpty { "No update source answered" })
    }

    fun download(info: UpdateInfo, custom: String? = null) {
        val asset = info.android ?: return
        val token = token(custom)
        val url = asset.apiUrl?.takeIf { token != null } ?: asset.url
        state.value = State.Downloading(info, 0f)
        Thread({
            try {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val apk = File(dir, "Tether-${info.version}.apk")
                val sha = fetch(url, token, apk) { p -> state.value = State.Downloading(info, p) }
                asset.sha256?.let { if (!it.equals(sha, ignoreCase = true)) throw IOException("checksum mismatch") }
                verifyArchive(apk, asset.versionCode)
                state.value = State.Installing(info)
                install(apk)
            } catch (e: IOException) {
                Log.w("update.download_failed", "reason" to e.message)
                state.value = State.Failed(e.message ?: "download failed")
            } catch (e: SecurityException) {
                Log.w("update.refused", "reason" to e.message)
                state.value = State.Failed(e.message ?: "refused")
            }
        }, "tether-update-dl").start()
    }

    /** Same package, newer version, same signing certificate as the running app. */
    private fun verifyArchive(apk: File, expectedCode: Long) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0
        val info = pm.getPackageArchiveInfo(apk.path, flags) ?: throw IOException("not a valid APK")
        if (info.packageName != context.packageName) throw SecurityException("different app (${info.packageName})")
        val mine = pm.getPackageInfo(context.packageName, flags)
        if (info.longCode() <= mine.longCode()) throw IOException("not newer than the installed build")
        if (expectedCode > 0 && info.longCode() != expectedCode) throw IOException("unexpected version code")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val a = certs(info)
            val b = certs(mine)
            if (a.isEmpty() || a != b) throw SecurityException("signed with a different key")
        }
    }

    private fun certs(p: PackageInfo): Set<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptySet()
        val s = p.signingInfo ?: return emptySet()
        val sigs = if (s.hasMultipleSigners()) s.apkContentsSigners else s.signingCertificateHistory
        return sigs.orEmpty().map { Hex.encode(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.longCode(): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()

    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("tether.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
        Log.i("update.install_committed")
    }

    fun failed(reason: String) {
        state.value = State.Failed(reason)
    }

    private fun get(url: String, token: String?): String {
        val c = open(url, token, binary = false)
        try {
            val code = c.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException(GitHubReleases.explain(code))
            return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    private fun fetch(url: String, token: String?, to: File, progress: (Float) -> Unit): String {
        val c = open(url, token, binary = true)
        val md = MessageDigest.getInstance("SHA-256")
        try {
            if (c.responseCode != HttpURLConnection.HTTP_OK) throw IOException(GitHubReleases.explain(c.responseCode))
            val total = c.contentLengthLong
            var done = 0L
            c.inputStream.use { input ->
                to.outputStream().use { out ->
                    val buf = ByteArray(BUF)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        if (done > MAX_APK) throw IOException("too large")
                        if (total > 0) progress(done.toFloat() / total)
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        return Hex.encode(md.digest())
    }

    /**
     * Opens [url], following redirects by hand: the Authorization header goes only to
     * api.github.com (GitHubReleases.headers), so the asset CDN never sees the token.
     */
    private fun open(url: String, token: String?, binary: Boolean): HttpURLConnection {
        var current = url
        repeat(MAX_REDIRECTS) {
            if (!current.startsWith("https://")) throw IOException("refusing a non-HTTPS URL")
            val c = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Tether-Android/${BuildConfig.VERSION_NAME}")
                GitHubReleases.headers(current, token, binary).forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val code = c.responseCode
            if (code !in REDIRECTS) return c
            val next = c.getHeaderField("Location")
            c.disconnect()
            current = URL(URL(current), next ?: throw IOException("redirect without a location")).toString()
        }
        throw IOException("too many redirects")
    }

    private fun host(u: String) = runCatching { URL(u).host }.getOrDefault(u)

    companion object {
        const val GITHUB_API = GitHubReleases.LATEST_URL
        private const val MAX_REDIRECTS = 5
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private const val TIMEOUT_MS = 15_000
        private const val BUF = 64 * 1024
        private const val MAX_APK = 200L * 1024 * 1024
        private const val MAX_REASON = 240
        @Volatile var instance: Updater? = null
    }
}

/** Receives the package installer's status; asks the user to confirm when Android needs it. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { c.startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i("update.installed")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                Log.w("update.install_failed", "status" to status, "msg" to msg)
                Updater.instance?.failed("Install failed: $msg")
            }
        }
    }
}
