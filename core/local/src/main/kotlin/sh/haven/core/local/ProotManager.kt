package sh.haven.core.local

import android.content.Context
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ProotManager"

/**
 * Manages the PRoot binary and Alpine Linux rootfs.
 *
 * PRoot is bundled as libproot.so in jniLibs (extracted to nativeLibraryDir
 * by Android, executable on Android 14+).
 *
 * The Alpine rootfs is downloaded on first use (~3MB compressed) and
 * extracted to filesDir/proot/rootfs/alpine/.
 */
@Singleton
class ProotManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    sealed class SetupState {
        data object NotInstalled : SetupState()
        data class Downloading(val progress: Int) : SetupState()
        data object Extracting : SetupState()
        data object Ready : SetupState()
        data class Error(val message: String) : SetupState()
    }

    private val _state = MutableStateFlow<SetupState>(SetupState.NotInstalled)
    val state: StateFlow<SetupState> = _state.asStateFlow()

    private val rootfsDir: File
        get() = File(context.filesDir, "proot/rootfs/alpine")

    val isRootfsInstalled: Boolean
        get() = File(rootfsDir, "bin/sh").exists()

    val prootBinary: String?
        get() {
            val nativeDir = context.applicationInfo.nativeLibraryDir
            val proot = File(nativeDir, "libproot.so")
            return if (proot.canExecute()) proot.absolutePath else null
        }

    val isReady: Boolean
        get() = prootBinary != null && isRootfsInstalled

    init {
        _state.value = if (isReady) SetupState.Ready else SetupState.NotInstalled
    }

    /**
     * Download and extract the Alpine Linux rootfs.
     * Safe to call if already installed — returns immediately.
     */
    suspend fun installRootfs() {
        if (isRootfsInstalled) {
            _state.value = SetupState.Ready
            return
        }

        try {
            _state.value = SetupState.Downloading(0)

            val arch = when {
                Build.SUPPORTED_ABIS.contains("arm64-v8a") -> "aarch64"
                Build.SUPPORTED_ABIS.contains("x86_64") -> "x86_64"
                else -> throw IllegalStateException("Unsupported ABI: ${Build.SUPPORTED_ABIS.toList()}")
            }

            val url = "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/$arch/alpine-minirootfs-3.21.3-$arch.tar.gz"
            val tarball = File(context.cacheDir, "alpine-minirootfs.tar.gz")

            // Download
            withContext(Dispatchers.IO) {
                Log.d(TAG, "Downloading rootfs: $url")
                val conn = URL(url).openConnection()
                val totalSize = conn.contentLength
                BufferedInputStream(conn.getInputStream()).use { input ->
                    FileOutputStream(tarball).use { output ->
                        val buf = ByteArray(8192)
                        var downloaded = 0L
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            output.write(buf, 0, n)
                            downloaded += n
                            if (totalSize > 0) {
                                _state.value = SetupState.Downloading(
                                    (downloaded * 100 / totalSize).toInt()
                                )
                            }
                        }
                    }
                }
                Log.d(TAG, "Download complete: ${tarball.length()} bytes")
            }

            // Extract
            _state.value = SetupState.Extracting
            withContext(Dispatchers.IO) {
                extractTarGz(tarball, rootfsDir)
                tarball.delete()
                Log.d(TAG, "Rootfs extracted to ${rootfsDir.absolutePath}")
            }

            _state.value = SetupState.Ready
        } catch (e: Exception) {
            Log.e(TAG, "Rootfs install failed", e)
            _state.value = SetupState.Error(e.message ?: "Installation failed")
        }
    }

    /**
     * Extract a .tar.gz file to a directory.
     * Uses the system tar command (available on Android since API 1).
     */
    private fun extractTarGz(tarball: File, destDir: File) {
        destDir.mkdirs()

        val process = ProcessBuilder(
            "tar", "xzf", tarball.absolutePath, "-C", destDir.absolutePath
        ).redirectErrorStream(true).start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw RuntimeException("tar extraction failed (exit $exitCode): $output")
        }

        // Ensure /bin/sh exists (sanity check)
        if (!File(destDir, "bin/sh").exists()) {
            throw RuntimeException("Extraction succeeded but bin/sh not found in rootfs")
        }
    }

    /**
     * Delete the rootfs to free space.
     */
    fun deleteRootfs() {
        rootfsDir.deleteRecursively()
        _state.value = SetupState.NotInstalled
    }
}
