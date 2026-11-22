package io.github.patevan9.glucose.installer

import android.content.Context
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * The ONLY place this app talks to the watch.
 *
 * Step 1 (read-only):
 *  - pair()     : wireless pairing with the 6-digit code shown on the watch
 *  - identify() : reads a fixed list of facts about the watch
 *
 * Install or update:
 *  - saveCopies()   : optional, READ-ONLY. Copies the Glucose Glance app and
 *    watch face that are on the watch to the phone's Downloads folder.
 *  - checkUpdate()  : READ-ONLY. Compares the watch's versions with the
 *    official files packed in this installer.
 *  - runUpdate()    : updates Glucose Glance and its watch face, only if each
 *    packed file is signed with the Glucose Glance key and is NEWER.
 *
 * There is deliberately NO uninstall, clear-data, settings change, downgrade
 * or free-form command here. Only the 2 Glucose Glance packages below can be installed.
 */
object WatchLink {

    private const val PHONE_NAME = "Glucose Glance Installer"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    // Glucose Glance packages.
    private const val APP_PACKAGE = "io.github.patevan9.glucose"
    private const val FACE_PACKAGE = "io.github.patevan9.glucose.watchface"

    /**
     * SHA-256 fingerprint of the Glucose Glance signing certificate.
     * Packed Glucose Glance files must be signed with exactly this key.
     */
    private const val GLUCOSE_CERT_SHA256 =
        "44521be5d3bd31d40a72c81264cc2c75ab0338a702843f517bb5ebd154efb1bd"

    /** What Test 2 may install, in this order: the app first, then the watch face. */
    data class Bundled(val pkg: String, val label: String, val asset: String, val fileLabel: String)
    val BUNDLED = listOf(
        Bundled(APP_PACKAGE, "Glucose Glance app", "glucose-app.apk", "app"),
        Bundled(FACE_PACKAGE, "Glucose Glance watch face", "glucose-watchface.apk", "watchface"),
    )

    private var keyReady = false

    /**
     * The phone needs its own "ADB key" so the watch can remember it after pairing.
     * It is created once and kept in this app's private storage (never backed up,
     * never sent anywhere). This is NOT a Glucose Glance signing key.
     */
    fun setUp(context: Context) {
        if (keyReady) return
        val keyFile = File(context.filesDir, "installer-adbkey.pem")
        KadbCert.configure(OkioFilePrivateKeyStore(keyFile.toOkioPath()))
        keyReady = true
    }

    /** Pair using the code from the watch's "Pair new device" screen. */
    suspend fun pair(host: String, port: Int, code: String) {
        require(code.length == 6 && code.all { it.isDigit() }) { "The pairing code must be 6 digits." }
        Kadb.pair(host, port, code, PHONE_NAME)
    }

    /** Connect and read facts about the watch. Read-only. */
    suspend fun identify(host: String, port: Int): WatchInfo = withContext(Dispatchers.IO) {
        Kadb.create(host, port, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS).use { watch ->
            fun prop(name: String): String = read(watch, "getprop $name")

            WatchInfo(
                manufacturer = prop("ro.product.manufacturer"),
                model = prop("ro.product.model"),
                deviceName = prop("ro.product.device"),
                androidVersion = prop("ro.build.version.release"),
                sdkLevel = prop("ro.build.version.sdk"),
                characteristics = prop("ro.build.characteristics"),
                cpu = prop("ro.product.cpu.abi"),
                serial = prop("ro.serialno"),
                appVersion = installedVersion(watch, APP_PACKAGE),
                faceVersion = installedVersion(watch, FACE_PACKAGE),
            )
        }
    }

    /**
     * READ-ONLY handshake test: does the watch still trust (is it paired with) this phone?
     * true = yes, false = needs pairing, null = could not tell (for example, no answer).
     */
    suspend fun isPaired(host: String, port: Int): Boolean? = withContext(Dispatchers.IO) {
        try {
            Kadb.create(host, port, 5_000, 8_000).use { watch -> read(watch, "getprop ro.product.model") }
            true
        } catch (e: com.flyfishxu.kadb.exception.AdbPairAuthException) {
            false
        } catch (e: com.flyfishxu.kadb.exception.AdbAuthException) {
            false
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- Install or update ----------------

    /**
     * Step 0 (READ-ONLY on the watch): copies the Glucose Glance app and watch face
     * that are installed on the watch to the phone's Downloads, folder
     * "Glucose Glance backup". Returns the file names saved.
     */
    suspend fun saveCopies(context: Context, host: String, port: Int): List<String> = withContext(Dispatchers.IO) {
        val saved = mutableListOf<String>()
        Kadb.create(host, port, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS).use { watch ->
            for (b in BUNDLED) {
                val version = installedVersion(watch, b.pkg) ?: continue
                val paths = read(watch, "pm path ${b.pkg}").lines()
                    .map { it.trim() }.filter { it.startsWith("package:") }.map { it.removePrefix("package:") }
                check(paths.isNotEmpty()) { "Could not find the ${b.label} files on the watch." }
                for ((i, remote) in paths.withIndex()) {
                    val suffix = if (i == 0) "" else "-part${i + 1}"
                    val name = "Glucose-Glance-${version}-${b.fileLabel}$suffix.apk"
                    val tmp = File(context.cacheDir, name)
                    try {
                        watch.pull(tmp, remote) // copy FROM the watch, nothing is changed there
                        check(tmp.length() > 0) { "The copy of $name is empty." }
                        PhoneDownloads.save(context, tmp, name)
                        saved += name
                    } finally {
                        tmp.delete()
                    }
                }
            }
        }
        saved
    }

    enum class Action { INSTALL, UPDATE, SAME, WATCH_IS_NEWER, NOT_SUPPORTED }

    /** The watch face uses Watch Face Format: Wear OS 4 (API 33) or newer. */
    private fun faceSupported(watch: Kadb) =
        (read(watch, "getprop ro.build.version.sdk").toIntOrNull() ?: 0) >= 33

    data class PlanItem(
        val bundled: Bundled,
        val onWatchName: String?, val onWatchCode: Long?,
        val newName: String, val newCode: Long,
        val action: Action,
    )

    /** READ-ONLY: what an update would do. Also checks the packed files are genuine. */
    suspend fun checkUpdate(context: Context, host: String, port: Int): List<PlanItem> = withContext(Dispatchers.IO) {
        Kadb.create(host, port, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS).use { watch ->
            val faceOk = faceSupported(watch)
            BUNDLED.map { b ->
                withVerifiedApk(context, b) { _, newName, newCode ->
                    val onName = installedVersion(watch, b.pkg)
                    val onCode = if (onName != null) installedVersionCode(watch, b.pkg) else null
                    val action = when {
                        b.pkg == FACE_PACKAGE && !faceOk -> Action.NOT_SUPPORTED
                        onName == null -> Action.INSTALL
                        onCode == null -> error("Could not read the ${b.label} version on the watch.")
                        newCode > onCode -> Action.UPDATE
                        newCode == onCode -> Action.SAME
                        else -> Action.WATCH_IS_NEWER
                    }
                    PlanItem(b, onName, onCode, newName, newCode, action)
                }
            }
        }
    }

    /**
     * Updates (or installs) the app, then the watch face. Stops at the first problem.
     * Re-checks everything right before installing. Never downgrades, never uninstalls.
     * [progress] receives one line per step.
     */
    suspend fun runUpdate(context: Context, host: String, port: Int, progress: (String) -> Unit): List<String> =
        withContext(Dispatchers.IO) {
            val done = mutableListOf<String>()
            Kadb.create(host, port, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS).use { watch ->
                val faceOk = faceSupported(watch)
                for (b in BUNDLED) {
                    if (b.pkg == FACE_PACKAGE && !faceOk) {
                        progress("${b.label}: needs Wear OS 4 or newer. Skipped.")
                        continue
                    }
                    withVerifiedApk(context, b) { apk, newName, newCode ->
                        val onName = installedVersion(watch, b.pkg)
                        val onCode = if (onName != null) installedVersionCode(watch, b.pkg) else null
                        when {
                            onName == null -> {
                                progress("Installing ${b.label} $newName…")
                                watch.install(apk) // new install
                            }
                            onCode == null -> error("Could not read the ${b.label} version on the watch. Stopped.")
                            newCode > onCode -> {
                                progress("Updating ${b.label} $onName → $newName…")
                                watch.install(apk, "-r") // replace = update, keeps data. No -d (downgrade).
                            }
                            else -> {
                                progress("${b.label}: already $onName. Skipped.")
                                return@withVerifiedApk
                            }
                        }
                        val nowCode = installedVersionCode(watch, b.pkg)
                        check(nowCode == newCode) { "The watch did not confirm ${b.label} $newName. Stopped." }
                        done += "${b.label} $newName"
                        progress("✅ ${b.label} $newName")
                    }
                }
            }
            done
        }

    /**
     * Copies a packed file to private storage, checks it is the expected package
     * and is signed with the Glucose Glance key, then runs [block] with it.
     */
    private inline fun <T> withVerifiedApk(context: Context, b: Bundled, block: (File, String, Long) -> T): T {
        check(b in BUNDLED) { "Blocked: not an allowed package." }
        val apk = File(context.cacheDir, b.asset)
        context.assets.open(b.asset).use { input -> apk.outputStream().use { input.copyTo(it) } }
        try {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageArchiveInfo(
                apk.path, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
            ) ?: error("The packed ${b.label} file is damaged.")
            check(info.packageName == b.pkg) { "Blocked: the packed ${b.label} file is the wrong app." }
            val signers = info.signingInfo?.apkContentsSigners.orEmpty()
            val genuine = signers.isNotEmpty() && signers.all { sha256(it.toByteArray()) == GLUCOSE_CERT_SHA256 }
            check(genuine) { "Blocked: the packed ${b.label} file is not signed with the Glucose Glance key." }
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
            return block(apk, info.versionName ?: "?", code)
        } finally {
            apk.delete()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Installed version code (for example 14), or null if unknown. */
    private fun installedVersionCode(watch: Kadb, pkg: String): Long? =
        Regex("""versionCode=(\d+)""").find(read(watch, "dumpsys package $pkg"))?.groupValues?.get(1)?.toLongOrNull()

    /** Returns the installed version name, or null if the package is not installed. */
    private fun installedVersion(watch: Kadb, pkg: String): String? {
        val listed = read(watch, "pm list packages $pkg")
            .lines().any { it.trim() == "package:$pkg" }
        if (!listed) return null
        val line = read(watch, "dumpsys package $pkg")
            .lines().firstOrNull { it.trim().startsWith("versionName=") }
        return line?.substringAfter("versionName=")?.trim() ?: "installed (version unknown)"
    }

    /**
     * Runs one of the fixed read-only commands above.
     * Only called with commands written in this file, never with user-typed text.
     */
    private fun read(watch: Kadb, command: String): String {
        check(READ_ONLY.any { command.startsWith(it) }) { "Blocked: not a read-only command" }
        return watch.shell(command).output.trim()
    }

    private val READ_ONLY = listOf("getprop ", "pm list packages ", "dumpsys package ", "pm path ")
}

/** What we learned about the watch. */
data class WatchInfo(
    val manufacturer: String,
    val model: String,
    val deviceName: String,
    val androidVersion: String,
    val sdkLevel: String,
    val characteristics: String,
    val cpu: String,
    /** Used only to recognise this watch in the Wi-Fi search list. Kept on the phone. */
    val serial: String,
    val appVersion: String?,
    val faceVersion: String?,
) {
    val isWatch: Boolean get() = characteristics.contains("watch")

    /** Wear OS version from the Android API level (Wear OS 3 = API 30). */
    val wearOs: String
        get() = when (sdkLevel.toIntOrNull()) {
            30 -> "Wear OS 3"
            33 -> "Wear OS 4"
            34 -> "Wear OS 5"
            36 -> "Wear OS 6"
            null -> "unknown"
            else -> "Android API $sdkLevel"
        }

    /** Glucose Glance's watch face needs Watch Face Format, Wear OS 4 (API 33) or newer. */
    val faceSupported: Boolean get() = (sdkLevel.toIntOrNull() ?: 0) >= 33
}
